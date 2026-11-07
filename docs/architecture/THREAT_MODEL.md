# Threat Model

Written by `P15-TSK-001` (2026-11-08), Phase 15's first task and its security baseline. Every later Phase 15 task
builds a control this register names, and the register is held by `ThreatModelIsCompleteTest`
(`com.finapp.app.architecture`). The controls are decided in ADR-0120…ADR-0125 (`Proposed`) and in the ADRs each row
cites; this document decides nothing. It names threats, rates them by a written rule, and maps each one to a control
with evidence, to a task that will build one, or to an acceptance that only the owner can give.

**What this document is not.** It claims no compliance with, certification under or attestation against any standard
(`PHASE_15_PLAN.md` §1.1). It maps controls to threats and to nothing else. It holds no secret, credential, endpoint or
hostname, and describes a weakness only at the level of its class, never as a way to exploit it. Configuration
property names appear where a control is named after one; their values never do.

---

## 1. How to read this register

### 1.1 Scope

The deployed platform as `PHASE_15_PLAN.md` §4 defines it: the units that exist, not invented microservices.

| Unit | Runs as | Holds authoritative state |
|---|---|---|
| `finapp-app` | N ≥ 3 replicas of one image. Every replica serves HTTP (customer, merchant, provider callbacks, operator) and runs every leaderless schedule, inbox consumer and the outbox relay | No. Sessions, idempotency records, leases, outbox and inbox all live in PostgreSQL |
| `finapp-migrator` | A Job per release, before the rollout | No (Flyway's schema history, serialised by Flyway's lock) |
| PostgreSQL | Managed, outside the cluster (C3) | Everything authoritative: the journal, the audit record, the evidence |
| Kafka | Managed, outside the cluster (C3) | Nothing. Events are hints and transport, never truth (`INV-EVT-02`) |
| Telemetry | Collector, metrics, alerting, trace and log back ends, in the cluster | Diagnostic only |
| Edge | A public ingress and an internal operator ingress (C9) | None |

**Drawn for N replicas.** Nothing in this register says "the server". A request, a callback or a consumed record can
land on any replica, and two replicas can handle copies of it at the same instant. Two releases run side by side for
the length of a rollout. A replica can die between commit and response. Threats that exist *only because* there are N
replicas are modelled as threats: version skew (T12), a stepped instance clock (T13), the connection budget (T14),
duplicate commands and events (T16, T17), and a client address read from a header (T40).

**Today and target.** Phase 15 has built nothing yet. "Today" means the repository at this commit: the application,
its guards and tests, the local stack, CI. There is no image, manifest, edge, cloud account or deployed instance.
The edge and network threats are therefore answered by the Phase 15 tasks that will build those things, and the
register says so row by row.

### 1.2 STRIDE

Each boundary in §2 answers all six categories: **S**poofing (a party or system claiming to be another),
**T**ampering (an unauthorised change to data, code or configuration), **R**epudiation (an act that cannot be tied to
its actor, or whose record can be lost), **I**nformation disclosure, **D**enial of service, and **E**levation of
privilege. A category answers with the threats that apply at that boundary, or with `N/A` and the reason none does.

### 1.3 The rating rule

A threat is rated **inherently**: as the platform would be deployed under C1–C15 *before* any control specific to
that threat. Ratings therefore do not fall as controls land; the disposition carries the controls. **Likelihood** is
who can attempt the threat, or how often it arises unprompted:

- **HIGH**: anyone who can reach a public route, or a condition that every release, retry, rebalance or provider
  interaction produces.
- **MEDIUM**: needs one foothold (a stolen credential, an insider, a compromised dependency or pod, a position inside
  the private network, an operator's mistake), or an infrastructure event that happens in normal operation but not on
  every request (a clock step, a node loss, a broker outage).
- **LOW**: needs two footholds at once, or a rare infrastructure failure.

**Impact** is the worst credible outcome:

- **SEVERE**: money created, destroyed or moved without authority; financial history or evidence altered without
  detection; the restricted data of many parties disclosed; the platform's own credentials or keys disclosed.
- **HIGH**: one party's money or restricted data exposed or misused; a privileged account taken over; audit or
  evidence lost; a financial outcome wrong until reconciliation catches it.
- **MEDIUM**: service degraded or refused; internal operational information disclosed; a record wrong in a
  non-financial field.
- **LOW**: a nuisance with no money, no restricted data and no audit consequence.

The rating is the cell of this table, and nothing else. The guard recomputes every rating from it.

| Impact \ Likelihood | HIGH | MEDIUM | LOW |
|---|---|---|---|
| SEVERE | CRITICAL | CRITICAL | HIGH |
| HIGH | HIGH | HIGH | MEDIUM |
| MEDIUM | MEDIUM | MEDIUM | LOW |
| LOW | LOW | LOW | LOW |

### 1.4 Dispositions and evidence

Every threat has exactly one disposition:

- **`CONTROL`**: one or more rows in §4 answer it. A threat may carry a residual risk beside its controls (§5).
- **`ACCEPTED by <owner> on <YYYY-MM-DD>`**, optionally followed by `, until <YYYY-MM-DD>`: the owner accepted the risk
  on that date. **Only the owner accepts a risk.** No task records an acceptance on the owner's behalf, and an
  acceptance past its `until` date turns the guard red. A scan finding accepted under `PHASE_GATES.md` §5 Phase 15
  criterion 2 carries an `until`.
- **`PROPOSED ACCEPTANCE`**: a task recommends acceptance and the owner has not decided. This is never allowed for a
  CRITICAL or HIGH threat: the guard is red until the owner accepts or a control is named.

A row in §4 has one of three states, and its evidence column must match its state:

- **`EXISTS`**: the control is built. The evidence is one or more repository paths in backticks, each a file that
  exists. `Class.java#method` names the test method that holds the claim, wherever the class is broader than the claim.
  A policy or a file (a migration, a CI workflow, a pin list) counts as evidence when the file *is* the control.
- **`PLANNED`**: a Phase 15 task builds it. The evidence is the task's id.
- **`REMEDIATION`**: an earlier remediation entry (`P14-REM-*`) or a cross-cutting item (`X-TSK-*`) builds it, outside
  the Phase 15 milestones. The evidence is the entry's id. This is the honest answer where that entry is the only owner.

### 1.5 Keeping it true

`ThreatModelIsCompleteTest` fails the build when:

- one of the ten boundaries of §2 is missing or renamed, or any boundary leaves a STRIDE category unanswered;
- the matrix of §2 and the register of §3 disagree about which threat sits at which boundary and category;
- a rating is not the cell of §1.3's table, or the table itself has changed;
- a threat has no disposition, a `CONTROL` threat has no row, or a CRITICAL or HIGH threat is neither controlled nor
  accepted by the owner;
- an `EXISTS` row names a file that does not exist, or a `#method` its file does not declare; a `PLANNED` or
  `REMEDIATION` row names an id that is not a backlog entry; or any task id in this document is not a backlog entry;
- **a `PLANNED` or `REMEDIATION` row names a task that is no longer live** (`PLANNED`, `READY` or `IN_PROGRESS`). When
  a task's status becomes `COMPLETE`, its gate must replace the promise with the evidence it built. When a task is
  postponed, its promise has no owner, and its row must become a gap. A row naming two tasks goes red when either one
  finishes, so the gate splits it into an `EXISTS` half and a `PLANNED` remainder;
- it parses fewer boundaries, threats, cells or rows than the minimums it records, so a renamed heading or a broken
  table cannot pass as an empty register.

Add a threat at the end, with the next free number. Never renumber a threat: other documents and tasks cite these
numbers.

---

## 2. Trust boundaries

The ten boundaries of `PHASE_15_PLAN.md` §9.1. Each is named for its direction: who is on the outside and what they
reach.

### B1 — Internet → edge

Customers' devices, merchants' servers, providers and anyone else on the internet reach the **public ingress**. Today
no edge exists: the application's own port is the boundary. In the target, the edge terminates TLS and serves an
allow-list of customer, merchant and provider routes (C9, `P15-TSK-011`), and forwards each request to any of the N
replicas with no affinity, since sessions live in PostgreSQL.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T1, T20 | Guessing or replaying a customer's credentials at the public surface |
| Tampering | T21 | |
| Repudiation | N/A | The edge records no business act and makes no decision. An act is recorded where it is decided, past B2, and a customer's repudiation is modelled there (T26) |
| Information disclosure | T2, T21, T31 | |
| Denial of service | T1 | |
| Elevation of privilege | T31 | |

### B2 — Edge → application

The edge forwards to the `finapp-app` Service, which reaches any of N replicas. Everything past this boundary is the
application's: it authenticates (opaque sessions, merchant API keys, checkout tokens, callback signatures), authorises
(permissions and ownership), and decides. Every replica must reach the same decision about the same request, so every
arbitration lives in PostgreSQL.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T20, T40 | |
| Tampering | T16 | A retried, duplicated or replayed command, which may land on two replicas at once |
| Repudiation | T26 | |
| Information disclosure | T2, T19 | |
| Denial of service | T1, T14, T41 | |
| Elevation of privilege | T4, T19 | |

### B3 — Operator network → operator ingress

Operators (ledger, reconciliation, risk, accounting, lending and BNPL staff) reach the routes an operator permission
guards: `/v1/operator/**` and the operator doors elsewhere. In the target, these routes are served only by the
internal ingress (C9, `P15-TSK-011`). Today they share the application's single port with every other route.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T3 | |
| Tampering | T18 | |
| Repudiation | T38 | |
| Information disclosure | T3 | |
| Denial of service | N/A | Only authenticated operators on the internal network reach this ingress. Each request is bounded by the application's request limits, and the replicas that serve it are B2's, whose exhaustion T14 models |
| Elevation of privilege | T3, T18 | |

### B4 — Application → PostgreSQL

Every replica holds a pool of connections as `finapp_app`, and the migrator Job connects as `finapp_migrator`. This is
the boundary to the financial record: the journal, the audit record and the evidence. The database's own rules
(grants, `CHECK`s, triggers) are the controls that bind every writer, including one that bypasses the domain.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T36 | |
| Tampering | T13, T15, T35 | |
| Repudiation | T10 | |
| Information disclosure | T7, T36 | |
| Denial of service | T14, T37 | |
| Elevation of privilege | T15, T33, T44 | |

### B5 — Application → Kafka

Every replica's outbox relay publishes, and every replica's inbox consumers read, in consumer groups per module.
Kafka carries hints and transport. The outbox in PostgreSQL is the durable source, and the inbox deduplicates what the
broker redelivers.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T9 | |
| Tampering | T9, T12, T17 | |
| Repudiation | N/A | Kafka holds no financial or audit record. Every published event is first an outbox row committed with the act it announces, and the act's audit record is in PostgreSQL (`INV-EVT-01`, `INV-EVT-02`), so a disputed message is answered from those, never from the broker |
| Information disclosure | T9 | |
| Denial of service | T25 | |
| Elevation of privilege | T9 | An injected event acting with the platform's authority, since consumers act as the platform |

### B6 — Application → providers

Every replica calls the provider adapters' endpoints (simulated providers in this programme): payment, instant and
corridor rails, tokenisation, KYC, credit data, FX, payouts and the settlement and statement sources. Each call
carries the platform's own credential for that provider.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T22 | |
| Tampering | T22 | |
| Repudiation | T24 | |
| Information disclosure | T4, T22 | |
| Denial of service | T23 | |
| Elevation of privilege | T43 | |

### B7 — Providers → callback routes

Providers call the application's callback routes, `/v1/providers/**`, through the public edge. Five callback doors
exist in the code: KYC callbacks, payment webhooks, instant-rail webhooks, FX webhooks and corridor webhooks. Each is
present only where its provider is configured, and the first three are in the published contract. Any replica receives
any delivery.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T8 | |
| Tampering | T8 | |
| Repudiation | T24 | |
| Information disclosure | T8 | |
| Denial of service | T1 | |
| Elevation of privilege | T8 | A forged callback adopting an outcome moves money with the provider's authority |

### B8 — CI → registry

CI builds the application and migrator images from a reviewed commit and pushes them to the registry (C7). Today CI
builds, tests and scans, and pushes nothing.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T27 | |
| Tampering | T5, T27 | |
| Repudiation | T27 | |
| Information disclosure | T6 | |
| Denial of service | T42 | |
| Elevation of privilege | T5, T30 | |

### B9 — GitOps → cluster

The GitOps controller applies the environment's overlay, pinned by digest, to the cluster. It runs the migrator Job
before the rollout, then rolls the N replicas (C8).

| Category | Threats | Note |
|---|---|---|
| Spoofing | T27 | |
| Tampering | T11, T12, T28 | |
| Repudiation | T28 | |
| Information disclosure | T6 | |
| Denial of service | T12 | |
| Elevation of privilege | T28, T33 | |

### B10 — Humans → cluster and cloud

Engineers and operators reaching the cluster's API, the cloud account, the secret store, the key service, the
database's administrative access, the backups and the telemetry back ends.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T29 | |
| Tampering | T11, T39 | |
| Repudiation | T10, T39 | |
| Information disclosure | T6, T32, T34 | |
| Denial of service | T32, T34 | |
| Elevation of privilege | T29 | |

---

## 3. The threat register

`Where` lists each boundary and STRIDE category at which the threat sits, as `B<n>:<S|T|R|I|D|E>`. T1–T14 carry the
numbers `PHASE_15_PLAN.md` §9.2 gave them.

| ID | Threat | Where | Likelihood | Impact | Rating | Disposition |
|---|---|---|---|---|---|---|
| T1 | Credential stuffing, registration abuse and volumetric abuse from the internet | B1:S, B1:D, B2:D, B7:D | HIGH | HIGH | HIGH | CONTROL |
| T2 | Reconnaissance through operational endpoints (the actuator, health detail, build and machine identity) | B1:I, B2:I | HIGH | MEDIUM | MEDIUM | CONTROL |
| T3 | A compromised operator account, or a malicious insider acting within or beyond a role | B3:S, B3:I, B3:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T4 | Lateral movement and exfiltration from a compromised application pod | B2:E, B6:I | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T5 | A compromised dependency, base image, CI action or tool in the supply chain | B8:T, B8:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T6 | Secret disclosure through the repository, an image, a manifest, a log, an environment dump or a heap dump | B8:I, B9:I, B10:I | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T7 | Database compromise or exfiltration of the financial record and restricted data | B4:I | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T8 | Forged, tampered or replayed provider callbacks | B7:S, B7:T, B7:I, B7:E | HIGH | SEVERE | CRITICAL | CONTROL |
| T9 | Message injection, tampering or reading on Kafka | B5:S, B5:T, B5:I, B5:E | MEDIUM | HIGH | HIGH | CONTROL |
| T10 | Audit tampering or loss | B4:R, B10:R | MEDIUM | HIGH | HIGH | CONTROL |
| T11 | Configuration drift and unreviewed manual change | B9:T, B10:T | MEDIUM | HIGH | HIGH | CONTROL |
| T12 | A deployment breaking financial consistency or availability: version skew across replicas, a migration, an event or handler change | B5:T, B9:T, B9:D | HIGH | SEVERE | CRITICAL | CONTROL |
| T13 | A stepped instance clock deciding a financial instant | B4:T | MEDIUM | HIGH | HIGH | CONTROL |
| T14 | Resource exhaustion and noisy neighbours, the database connection budget above all | B2:D, B4:D | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T15 | A raw writer: a write reaching financial tables outside the ledger's doors, from a code defect or from anyone holding the application's database credential | B4:T, B4:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T16 | A retried, duplicated or replayed command producing a second financial effect, including two copies landing on two replicas at once | B2:T | HIGH | SEVERE | CRITICAL | CONTROL |
| T17 | A duplicated, redelivered or replayed event producing a second effect | B5:T | HIGH | SEVERE | CRITICAL | CONTROL |
| T18 | An operator acting alone where four eyes are required | B3:T, B3:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T19 | Broken object-level authorisation: a customer or merchant reading or acting on another's resource | B2:I, B2:E | HIGH | HIGH | HIGH | CONTROL |
| T20 | A stolen bearer credential (session token, merchant API key, checkout token) replayed | B1:S, B2:S | MEDIUM | HIGH | HIGH | CONTROL |
| T21 | Client traffic intercepted or altered in transit to the edge | B1:T, B1:I | MEDIUM | HIGH | HIGH | CONTROL |
| T22 | Impersonation or interception on the outbound provider hop | B6:S, B6:T, B6:I | MEDIUM | HIGH | HIGH | CONTROL |
| T23 | A provider unavailable, slow or answering an unknown state, and the outcome mishandled into a duplicate or a lost movement | B6:D | HIGH | SEVERE | CRITICAL | CONTROL |
| T24 | A provider's answer or callback disputed after its evidence was lost | B6:R, B7:R | MEDIUM | HIGH | HIGH | CONTROL |
| T25 | Kafka unavailable or lagging | B5:D | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T26 | A customer or merchant repudiating an act | B2:R | MEDIUM | HIGH | HIGH | CONTROL |
| T27 | An image substituted between build and run, or an image without provenance admitted | B8:S, B8:T, B8:R, B9:S | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T28 | An unreviewed or single-person change reaching production | B9:T, B9:R, B9:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T29 | A human's cluster or cloud administrative credential compromised or abused | B10:S, B10:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T30 | CI as a path to production: a workflow or a CI credential reaching an environment | B8:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T31 | The operator surface reachable from the internet | B1:I, B1:E | MEDIUM | HIGH | HIGH | CONTROL |
| T32 | A backup disclosed, or not restorable when needed | B10:I, B10:D | LOW | SEVERE | HIGH | CONTROL |
| T33 | The migrator's credential or Job misused to alter the schema or financial data | B4:E, B9:E | LOW | SEVERE | HIGH | CONTROL |
| T34 | A field-level key version leaked, or lost so that evidence under it cannot be read | B10:I, B10:D | LOW | HIGH | MEDIUM | CONTROL |
| T35 | A ciphertext moved to another row and decrypted as that row's evidence | B4:T | LOW | HIGH | MEDIUM | CONTROL |
| T36 | Impersonation or interception of the database hop | B4:S, B4:I | LOW | SEVERE | HIGH | CONTROL |
| T37 | Unbounded growth of operational tables (outbox, inbox, audit) | B4:D | HIGH | MEDIUM | MEDIUM | CONTROL |
| T38 | An operator's act recorded under a customer's actor type, or a refused privileged attempt left unrecorded | B3:R | HIGH | MEDIUM | MEDIUM | CONTROL |
| T39 | A dead-lettered event resolved by an unaudited manual change | B10:T, B10:R | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T40 | A client's source address spoofed through forwarded headers, to evade a per-source limit or to mislead a record | B2:S | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T41 | One client's idempotency key value refusing another client's request | B2:D | LOW | LOW | LOW | CONTROL |
| T42 | The registry unavailable when a replica must start | B8:D | MEDIUM | MEDIUM | MEDIUM | PROPOSED ACCEPTANCE |
| T43 | A hostile or malformed provider answer driving an adapter outside its contract | B6:E | LOW | HIGH | MEDIUM | CONTROL |
| T44 | A report path able to write | B4:E | LOW | HIGH | MEDIUM | CONTROL |

**Summary.** 44 threats: 16 CRITICAL, 15 HIGH, 12 MEDIUM, 1 LOW. 43 are `CONTROL`. One, T42 (MEDIUM), is a
`PROPOSED ACCEPTANCE` awaiting the owner (§6). No threat is `ACCEPTED`.

---

## 4. Controls and evidence

One row per control claim. The claim says exactly what the evidence holds, and no more.

| Threat | Control | State | Evidence |
|---|---|---|---|
| T1 | Failed authentications counted per identity in PostgreSQL. Crossing the threshold locks the identity, and ten instances failing concurrently count every failure | EXISTS | `app/src/test/java/com/finapp/app/domain/AuthenticationLockoutDatabaseTest.java#crossingTheThresholdLocks`; `app/src/test/java/com/finapp/app/domain/AuthenticationLockoutDatabaseTest.java#theCounterIsNotBypassableByConcurrency` |
| T1 | Each of the five failing authentication paths performs exactly one credential derivation, so the cost of a guess does not reveal which identifiers exist (`INV-IDN-07`) | EXISTS | `app/src/test/java/com/finapp/app/domain/AuthenticationCostsTheSameDatabaseTest.java#everyFailingPathDoesTheWork` |
| T1 | A request body over the size limit is refused `413`, whether or not its length is declared | EXISTS | `app/src/test/java/com/finapp/app/api/RequestValidationTest.java#anOversizedBodyIsRefused`; `app/src/test/java/com/finapp/app/api/RequestValidationTest.java#aChunkedOversizedBodyIsRefused` |
| T1 | Coarse per-source limits at the public ingress | PLANNED | `P15-TSK-011` |
| T1 | A cluster-safe registration throttle and per-source limits on the unauthenticated routes, arbitrated in PostgreSQL | PLANNED | `P15-TSK-022` |
| T2 | The actuator is an allow-list: `env`, `beans`, `configprops`, `threaddump`, `loggers`, `mappings`, `metrics`, `conditions` and `scheduledtasks` answer `404` | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#theEndpointAllowListHolds` |
| T2 | `heapdump`, `shutdown` and `caches` answer `404` | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#theEndpointsBehindASecondGateAreAlsoAbsent` |
| T2 | A health response is a status and nothing else, and `info` carries build identity and nothing about the machine | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#healthPublishesStatusAndNothingElse`; `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#infoCarriesBuildIdentityOnly` |
| T2 | The actuator moved to a management port, bound to loopback outside Kubernetes | PLANNED | `P15-TSK-008` |
| T2 | NetworkPolicy admitting the management port from the kubelet and monitoring only | PLANNED | `P15-TSK-010` |
| T3 | Below `MULTI_FACTOR`, the interceptor refuses the manual adjustment's and the prior-period adjustment's approvals `403` before the permission is read | EXISTS | `app/src/test/java/com/finapp/app/ledger/ApprovalAssuranceTest.java#theRefusalIsTheInterceptors` |
| T3 | The period, rebuild and valuation-gap approvals are pinned at `MULTI_FACTOR` | EXISTS | `app/src/test/java/com/finapp/app/ledger/ApprovalAssuranceTest.java#thePeriodApprovalsArePinned`; `app/src/test/java/com/finapp/app/ledger/ApprovalAssuranceTest.java#theRebuildAndTheGapApprovalsStepUp` |
| T3 | MFA cannot be bypassed: elevating one session elevates none of the identity's others, using a session never raises its assurance, and the only endpoint that issues a session demands a verified code (`INV-IDN-05`) | EXISTS | `app/src/test/java/com/finapp/app/domain/MfaCannotBeBypassedDatabaseTest.java#anOlderPasswordSessionIsNotElevated`; `app/src/test/java/com/finapp/app/domain/MfaCannotBeBypassedDatabaseTest.java#aRefreshDoesNotRaiseAssurance`; `app/src/test/java/com/finapp/app/domain/MfaCannotBeBypassedDatabaseTest.java#theSessionIssuingEndpointDemandsAFactor` |
| T3 | No production path creates or elevates a session without being enumerated | EXISTS | `app/src/test/java/com/finapp/app/architecture/MfaBypassPathsAreEnumeratedTest.java#everyPathIsEnumerated` |
| T3 | Each role's permissions are code, and each role's exact grants are pinned | EXISTS | `identity/src/test/java/com/finapp/identity/RoleNameTest.java` |
| T3 | Every permission-guarded route requires exactly the permission the register names | EXISTS | `app/src/test/java/com/finapp/app/api/RoutePermissionRegisterTest.java#everyGuardedRouteRequiresItsRegisteredPermission` |
| T3 | A privileged endpoint refuses a session whose identity holds no role, and the denial is audited; a revoked role stops working on the next request | EXISTS | `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#negativeAuthorizationForPrivilegedEndpoints`; `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#aDenialIsAudited`; `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#roleRevocationIsImmediate` |
| T3 | Operator routes served only on the internal ingress | PLANNED | `P15-TSK-011` |
| T3 | Operator sessions audited as `OPERATOR`, refused step-ups audited, the privileged access review of role holdings | PLANNED | `P15-TSK-021` |
| T3 | Break-glass and compromise procedures, audited and rehearsed | PLANNED | `P15-TSK-032` |
| T4 | A minimal image: non-root, no shell, no package manager | PLANNED | `P15-TSK-003` |
| T4 | The restricted pod profile: read-only root, no capabilities, no privilege escalation, a seccomp profile, no service-account token | PLANNED | `P15-TSK-009` |
| T4 | Default-deny NetworkPolicy with an egress allow-list; Pod Security Admission `restricted`; no Kubernetes RBAC for the workload | PLANNED | `P15-TSK-010` |
| T4 | A workload identity that reads only its own secrets | PLANNED | `P15-TSK-016` |
| T5 | Every resolved dependency verified against a committed checksum. Signature verification is off: trust on first use, stated | EXISTS | `gradle/verification-metadata.xml` |
| T5 | CI's actions pinned by commit SHA, with weekly update proposals | EXISTS | `.github/workflows/ci.yml`; `.github/dependabot.yml` |
| T5 | Scanner images pinned by digest, and their freshness checked weekly | EXISTS | `infra/scanner-pins.sh`; `infra/scripts/check-pinned-images.sh` |
| T5 | The build's CycloneDX SBOM scanned for known vulnerabilities in CI | EXISTS | `infra/scripts/dependency-scan.sh` |
| T5 | Base images pinned by digest in a minimal multi-stage build | PLANNED | `P15-TSK-003` |
| T5 | Image scans, an SBOM per image and static analysis in CI, HIGH and CRITICAL failing | PLANNED | `P15-TSK-006` |
| T5 | Signatures and provenance, with admission refusing an unsigned image | PLANNED | `P15-TSK-007` |
| T6 | No credential literal in committed configuration except the one marked local default | EXISTS | `app/src/test/java/com/finapp/app/security/CommittedConfigurationHoldsNoSecretTest.java#noCommittedConfigurationHoldsACredentialLiteral` |
| T6 | The whole of git history scanned for secrets in CI | EXISTS | `infra/scripts/secret-scan.sh`; `.github/workflows/ci.yml` |
| T6 | A field or accessor named for a secret must be wrapped in `Sensitive`, which masks on every rendering path | EXISTS | `app/src/test/java/com/finapp/app/architecture/NoUnwrappedSecretRulesTest.java#rulesRejectTheirViolations` |
| T6 | The marked local database credential is refused against a database not on loopback; each confined credential's variable is the one its configuration binds | EXISTS | `app/src/test/java/com/finapp/app/security/DatabaseCredentialGuardTest.java#remoteIsRefused`; `app/src/test/java/com/finapp/app/security/ConfinedCredentialVariablesTest.java#theNamedVariableIsTheOneThatBinds` |
| T6 | No heap dump is served | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#theEndpointsBehindASecondGateAreAlsoAbsent` |
| T6 | Every secret inventoried and delivered from the secret store, with a committed `kind: Secret` carrying data refused | PLANNED | `P15-TSK-018` |
| T6 | Each workload's identity reads only its own secrets, under KMS keys per environment and purpose | PLANNED | `P15-TSK-016` |
| T6 | Humans denied `get secrets` | PLANNED | `P15-TSK-010` |
| T6 | Images scanned for secrets | PLANNED | `P15-TSK-006` |
| T6 | Rotation and revocation of every credential class, rehearsed | PLANNED | `P15-TSK-020` |
| T7 | Two database roles, `NOSUPERUSER NOCREATEDB NOCREATEROLE`, the application's and the migrator's | EXISTS | `infra/postgres/initdb/00-roles.sql` |
| T7 | The application role cannot become the migrator | EXISTS | `platform/src/test/java/com/finapp/platform/audit/ApplicationRoleGrantsTest.java#theRoleCannotEscalate` |
| T7 | The application role holds no DDL and cannot reach the migration history | EXISTS | `platform/src/test/java/com/finapp/platform/audit/ApplicationRoleGrantsTest.java#theRoleIsConfinedToData` |
| T7 | `sslmode=verify-full` required to a database off loopback, weaker modes refused at startup | EXISTS | `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#remoteWithoutTlsIsRefused`; `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#weakerModesAreRefused` |
| T7 | MFA secrets, KYC documents, payment and payout evidence encrypted field by field (AES-256-GCM), with tampering and the wrong key refused | EXISTS | `identity/src/test/java/com/finapp/identity/SecretCipherTest.java#tamperingIsRefused`; `kyc/src/test/java/com/finapp/kyc/DocumentCipherTest.java#tamperIsRefused`; `payments/src/test/java/com/finapp/payments/EvidenceCipherTest.java#tamperingAndWrongKeyRefuse`; `merchant/src/test/java/com/finapp/merchant/PayoutEvidenceCipherTest.java#tamperAndWrongKeyAreOneFailure` |
| T7 | Private subnets, managed volumes and backups encrypted under separate KMS keys, `rds.force_ssl` | PLANNED | `P15-TSK-015` |
| T7 | A private network with no public workload subnet | PLANNED | `P15-TSK-014` |
| T7 | Field-level keys as key rings, so a leaked version is bounded to its rows | PLANNED | `P15-TSK-019` |
| T7 | A `SELECT`-only reporting role (C13's cut candidate) | PLANNED | `P15-TSK-021` |
| T8 | KYC callbacks: HMAC-SHA256 over the raw request, compared in constant time, the wrong signature refused | EXISTS | `kyc/src/test/java/com/finapp/kyc/CallbackSignatureTest.java#aWrongSignatureIsRefused`; `kyc/src/test/java/com/finapp/kyc/CallbackSignatureTest.java#theComparisonIsConstantTime` |
| T8 | Payment webhooks: a signature that tampering breaks, and a two-sided freshness window | EXISTS | `payments/src/test/java/com/finapp/payments/WebhookSignatureTest.java#roundTripAndTamper`; `payments/src/test/java/com/finapp/payments/WebhookSignatureTest.java#theWindowIsTwoSided` |
| T8 | A delivery repeated three times lands one dedupe record, and every copy is retained as evidence | EXISTS | `app/src/test/java/com/finapp/app/payments/PaymentWebhookDatabaseTest.java#aTripleDeliveryLandsOneDedupeRecordAndThreeEvidenceRows` |
| T8 | FX callbacks are hints: a signed but forged callback is retained and moves nothing, and an unsigned or stale one is one uniform `401` | EXISTS | `app/src/test/java/com/finapp/app/fx/FxCallbackDuplicateDatabaseTest.java#aForgedSignedCallbackMovesNothing`; `app/src/test/java/com/finapp/app/fx/FxCallbackDuplicateDatabaseTest.java#unsignedOrStaleMovesNothing` |
| T8 | Callbacks as hints for the Phase 5 and Phase 7 providers, which still adopt a callback's own outcome | REMEDIATION | `X-TSK-015` |
| T8 | Provider callback routes served only by name on the public allow-list | PLANNED | `P15-TSK-011` |
| T8 | Callback secrets verified against the current and the previous secret during a rotation window | PLANNED | `P15-TSK-019` |
| T8 | A callback secret's rotation rehearsed | PLANNED | `P15-TSK-020` |
| T9 | A non-loopback bootstrap over `PLAINTEXT` refuses startup | EXISTS | `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#plaintextOffTheMachineIsRefused` |
| T9 | Lending's origination consumers treat an event as a hint: a forged or late event changes nothing the authoritative read would not | EXISTS | `lending/src/test/java/com/finapp/lending/LoanOriginationDatabaseTest.java#aForgedOrLateEventChangesNothing` |
| T9 | An event payload cannot carry a credential derivation | EXISTS | `app/src/test/java/com/finapp/app/domain/CredentialReachesNoEmittedSinkTest.java#anEventPayloadRefusesADerivation` |
| T9 | `SASL_SSL` with SCRAM per principal and ACLs per topic; the transport guard widened to require it off loopback | PLANNED | `P15-TSK-026` |
| T10 | The application role cannot `UPDATE` any column of, `DELETE` from or `TRUNCATE` the audit record | EXISTS | `platform/src/test/java/com/finapp/platform/audit/AuditImmutabilityTest.java#updateIsDeniedOnEveryColumn`; `platform/src/test/java/com/finapp/platform/audit/AuditImmutabilityTest.java#deleteIsDenied`; `platform/src/test/java/com/finapp/platform/audit/AuditImmutabilityTest.java#truncateIsDenied` |
| T10 | Every catalogued auditable action is referenced by production code, or declared not yet emitted with its owner | EXISTS | `app/src/test/java/com/finapp/app/audit/AuditCompletenessTest.java#everyActionIsEmittedOrDeclaredNotToBe` |
| T10 | The operator actor typed, and a refused step-up recorded | PLANNED | `P15-TSK-021` |
| T10 | The retention policy by data class, and the audit archival design to write-once storage | PLANNED | `P15-TSK-029` |
| T10 | Write-once (Object Lock) on the audit archive bucket | PLANNED | `P15-TSK-015` |
| T11 | Startup guards refuse an unsafe configuration on every replica: a pool beyond the connection budget, any configured `sslmode` source weaker than `verify-full`, a settlement pull source in cleartext | EXISTS | `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingGuardTest.java#scalingOutIsCaught`; `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#everyConfiguredSourceMustVerify`; `app/src/test/java/com/finapp/app/security/ProviderTransportGuardTest.java#startupConsultsEverySourceUrl` |
| T11 | Infrastructure as pinned, checked code, never applied by hand | PLANNED | `P15-TSK-013` |
| T11 | GitOps self-heal, and two persons on every production change | PLANNED | `P15-TSK-017` |
| T11 | The configuration register, and an alert when replicas disagree for longer than a rollout | PLANNED | `P15-TSK-031` |
| T12 | Migrations forward-only: `clean` disabled, validated before migrating, never out of order. The ledger's and the platform's builds set all three, as each of the 21 migrating modules' builds does | EXISTS | `ledger/build.gradle.kts`; `platform/build.gradle.kts` |
| T12 | Readiness reports down without the database, while liveness ignores it | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#readinessReflectsTheDatabase`; `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#livenessIgnoresTheDatabase` |
| T12 | The migrator image and Job, with Flyway's lock serialising two runs | PLANNED | `P15-TSK-004` |
| T12 | The expand-only lint (tightening DDL counted) and the schema-version readiness guard | PLANNED | `P15-TSK-005` |
| T12 | `maxUnavailable: 0` and `minReadySeconds` at least the grace period, in the base manifests | PLANNED | `P15-TSK-009` |
| T12 | Release N − 1 run on N's schema, the rolling update and the rollback rehearsed with the financial census | PLANNED | `P15-TSK-012` |
| T12 | The promotion refusing a rollback across a contract marker | PLANNED | `P15-TSK-017` |
| T12 | The event schema catalogue, compatibility against the last release, and the handler-set catalogue | PLANNED | `P15-TSK-027` |
| T12 | An audited replay over the rollout window for a handler added to an existing group | PLANNED | `P15-TSK-028` |
| T13 | Session liveness judged on the database's clock, never against a caller's instant | EXISTS | `app/src/test/java/com/finapp/app/architecture/SessionTimeIsTheDatabasesTest.java#noBoundIsComparedWithACallersInstant` |
| T13 | The five outbound stores stamp every send permit with the database's `statement_timestamp()` and bind no caller's instant | EXISTS | `app/src/test/java/com/finapp/app/architecture/SendPermitsAreTheDatabasesTest.java#everyPermitAssignmentIsTheDatabases` |
| T13 | A production read of ambient time fails the build | EXISTS | `app/src/test/java/com/finapp/app/architecture/NoAmbientTimeRulesTest.java#rulesRejectTheirViolations` |
| T13 | PI-013's and PI-014's clock-step defects fixed at their causes, each with a clock-step regression case | PLANNED | `P15-TSK-033` |
| T13 | The lock's grace and the submissions' orderings judged on the database's clock | REMEDIATION | `P14-REM-009` |
| T13 | Five stores still comparing a stored bound with a caller's instant | REMEDIATION | `X-TSK-008` |
| T14 | The connection budget is a startup guard: scaling out past the server's limit is refused, and the reserved connections count | EXISTS | `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingGuardTest.java#scalingOutIsCaught`; `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingGuardTest.java#reservedConnectionsAreLoadBearing` |
| T14 | Requests and limits, and an HPA bounded by the budget counting terminating pods | PLANNED | `P15-TSK-009` |
| T14 | `ResourceQuota` and `LimitRange` | PLANNED | `P15-TSK-010` |
| T14 | The budget's peak measured during a rollout and a pod kill | PLANNED | `P15-TSK-012` |
| T15 | An unbalanced journal entry is refused at commit, whoever writes it, raw SQL included (`INV-LED-01`) | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#aDirectUnbalancedInsertIsRefusedAtCommit` |
| T15 | The application role holds no `UPDATE`, `DELETE` or `TRUNCATE` on the journal | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#theColumnSweep` |
| T15 | The journal's append-only trigger binds even the migrator (`INV-HIST-01`) | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#theAppendOnlyTriggerBindsTheMigrator` |
| T15 | In code, the cash book has one poster: `CASH_AT_BANK` is named only by its poster and its reader | EXISTS | `app/src/test/java/com/finapp/app/architecture/CashAtBankHasOnePosterTest.java#cashAtBankIsNamedOnlyByItsPosterAndItsReader` |
| T15 | The Phase 14 schema closed to raw writers: a rebuild born undecided, a journal line joining only an entry of its own transaction, the GL mapping's version and activation locks | REMEDIATION | `P14-REM-004` |
| T15 | Write-offs and the BNPL fee pin enforced for every writer | REMEDIATION | `P14-REM-006` |
| T15 | The application role's `UPDATE (status)` on settlement sources revoked, or routed through a reviewed door | REMEDIATION | `P14-REM-007` |
| T15 | The application's database credential delivered only to the application's pods | PLANNED | `P15-TSK-018` |
| T15 | The database reachable only from the application's and the migrator's pods | PLANNED | `P15-TSK-010` |
| T15 | The database in private subnets | PLANNED | `P15-TSK-015` |
| T15 | The continuous trial balance and the proofs paging on their first breach, as detection | PLANNED | `P15-TSK-025` |
| T16 | Ten instances submitting one key produce exactly one transfer (`INV-IDEM-01`) | EXISTS | `app/src/test/java/com/finapp/app/transfers/TransferExecutionDatabaseTest.java#tenConcurrentIdenticalKeysProduceOneTransfer` |
| T16 | A key reused with a different request is refused (`INV-IDEM-03`) | EXISTS | `platform/src/test/java/com/finapp/platform/idempotency/IdempotentExecutorTest.java#differingFingerprintIsRejected` |
| T16 | A merchant's payout key is that merchant's own | EXISTS | `app/src/test/java/com/finapp/app/merchant/MerchantPayoutEndpointDatabaseTest.java#aClientKeyIsEachMerchantsOwn` |
| T16 | Every idempotency claim scoped to its principal, each money-moving scope moved behind a tested transition path | REMEDIATION | `X-TSK-003` |
| T16 | Posting once-ness's second rank ruled before any idempotency sweep (debt row #61) | PLANNED | `P15-TSK-002` |
| T16 | No idempotency record swept until that ruling | PLANNED | `P15-TSK-029` |
| T17 | A redelivered message produces no second effect, and its dedupe record commits with its effect (`INV-IDEM-04`) | EXISTS | `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#redeliveryProducesNoSecondEffect`; `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#theRecordAndTheEffectCommitTogether` |
| T17 | Reordering and duplication together leave the right answer under an ordering key (`INV-EVT-04`) | EXISTS | `platform/src/test/java/com/finapp/platform/inbox/InboxDeliveryOrderTest.java#reorderingAndDuplicationTogether` |
| T17 | Every topic's retention below the inbox's dedupe window, and cooperative rebalancing | PLANNED | `P15-TSK-026` |
| T17 | A handler added to an existing consumer group is caught by the handler-set catalogue | PLANNED | `P15-TSK-027` |
| T17 | A replay older than the dedupe window refused unless every consumer of the type is naturally idempotent | PLANNED | `P15-TSK-028` |
| T17 | The inbox sweep never deletes a record younger than its bound | PLANNED | `P15-TSK-029` |
| T17 | One effect per key counted after a rolling update and a pod kill | PLANNED | `P15-TSK-012` |
| T18 | A manual adjustment's approver differs from its proposer, as a `CHECK` every writer meets (`INV-AUD-04`) | EXISTS | `ledger/src/test/java/com/finapp/ledger/AdjustmentProposalMigrationTest.java#theFourEyesCheckIsTheInvariants` |
| T18 | The adjustment refuses self-approval, and the refusal is recorded in its own transaction | EXISTS | `ledger/src/test/java/com/finapp/ledger/AdjustmentProposalTest.java#selfApprovalIsRefusedByTheAggregate`; `app/src/test/java/com/finapp/app/ledger/LedgerAdjustmentsRefusalTest.java#aRefusedSelfApprovalIsRecordedInItsOwnTransaction` |
| T18 | No role holds both the proposal (or preparation) and the approval of one act: the six pairs the vocabulary names today | EXISTS | `identity/src/test/java/com/finapp/identity/RoleNameTest.java#noRoleProposesAndApprovesTheSameAct` |
| T18 | A period close's proposer cannot approve it | EXISTS | `accounting/src/test/java/com/finapp/accounting/PeriodClosesTest.java#theProposerCannotApprove` |
| T18 | A break's resolution under `MULTI_FACTOR`, and never one person's word for an aggregate subject | REMEDIATION | `P14-REM-001` |
| T18 | Step-up on the mapping activation and the acknowledgement approval | REMEDIATION | `P14-REM-002` |
| T18 | The privileged access review, including the propose/approve partition of role holdings | PLANNED | `P15-TSK-021` |
| T19 | An owner-scoped statement carries the owner predicate, and each owner-scoped operation has a named negative test | EXISTS | `app/src/test/java/com/finapp/app/architecture/OwnershipIsScopedTest.java#ownerScopedStatementsCarryThePredicate`; `app/src/test/java/com/finapp/app/architecture/OwnershipIsScopedTest.java#ownerScopedOperationsHaveNegativeTests` |
| T19 | A session-derived endpoint takes no identifier | EXISTS | `app/src/test/java/com/finapp/app/architecture/OwnershipIsScopedTest.java#sessionDerivedEndpointsTakeNoIdentifier` |
| T19 | Every merchant route answers another tenant's resource exactly as it answers an unknown one (`INV-MER-01`) | EXISTS | `app/src/test/java/com/finapp/app/merchant/MerchantTenancyBatteryDatabaseTest.java#everyAddressedRouteAnswersAnotherTenantsResourceAsUnknown` |
| T19 | Every handler declares its rule, and an undeclared endpoint is refused | EXISTS | `app/src/test/java/com/finapp/app/api/EveryEndpointDeclaresARuleTest.java#everyHandlerDeclaresSomething`; `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#anUndeclaredEndpointIsRefused` |
| T19 | The ownership detector widened to every module | REMEDIATION | `X-TSK-002` |
| T20 | A session token appears in no column of its stored row | EXISTS | `app/src/test/java/com/finapp/app/domain/SessionLifecycleDatabaseTest.java#theTokenIsNeverStored` |
| T20 | Revocation is immediate across instances (`INV-IDN-03`) | EXISTS | `app/src/test/java/com/finapp/app/domain/SessionRevocationDatabaseTest.java#revocationIsImmediateAcrossInstances` |
| T20 | A revoked merchant API key is refused immediately | EXISTS | `app/src/test/java/com/finapp/app/merchant/MerchantApiKeyDatabaseTest.java#aRevokedKeyRefusesImmediately` |
| T20 | The published contract carries a secret only in a request body, never in a path or a query | EXISTS | `app/src/test/java/com/finapp/app/domain/CredentialReachesNoEmittedSinkTest.java#thePublishedContractCarriesASecretOnlyInARequestBody` |
| T20 | TLS with HSTS at the edge | PLANNED | `P15-TSK-011` |
| T21 | TLS terminated at the edge, with HSTS and the security headers | PLANNED | `P15-TSK-011` |
| T21 | Ingress certificates renewed ahead of expiry, rehearsed | PLANNED | `P15-TSK-020` |
| T22 | Every provider base URL must be `https` off loopback, and every settlement source `https` or `sftp`; startup consults every source URL | EXISTS | `app/src/test/java/com/finapp/app/security/ProviderTransportGuardTest.java#plainHttpOffTheMachineIsRefusedForEveryProvider`; `app/src/test/java/com/finapp/app/security/ProviderTransportGuardTest.java#settlementSourcesAreHttpsOrSftpOffTheMachine`; `app/src/test/java/com/finapp/app/security/ProviderTransportGuardTest.java#startupConsultsEverySourceUrl` |
| T22 | An egress allow-list for the provider hop | PLANNED | `P15-TSK-010` |
| T23 | An aged unknown capture resolves to captured exactly once, and concurrent sweepers race to one winner (`INV-LIFE-03`) | EXISTS | `app/src/test/java/com/finapp/app/payments/PaymentSweeperDatabaseTest.java#anAgedCaptureUnknownResolvesToCapturedOnce`; `app/src/test/java/com/finapp/app/payments/PaymentSweeperDatabaseTest.java#concurrentSweepersRaceToOneWinner` |
| T23 | Send permits are stamped by the database's clock, so no instance's clock decides when an unconcluded send may be tried again | EXISTS | `app/src/test/java/com/finapp/app/architecture/SendPermitsAreTheDatabasesTest.java#everyPermitAssignmentIsTheDatabases` |
| T23 | The provider simulator reproduces every provider failure mode `CLAUDE.md` lists, and each is exercised | EXISTS | `app/src/test/java/com/finapp/app/architecture/ProviderFailureCoverageTest.java#everyNamedMethodIsExercised` |
| T24 | Every webhook delivery, duplicates included, is retained as evidence (`INV-HIST-02`) | EXISTS | `app/src/test/java/com/finapp/app/payments/PaymentWebhookDatabaseTest.java#aTripleDeliveryLandsOneDedupeRecordAndThreeEvidenceRows` |
| T24 | A late KYC callback is kept as evidence and decides nothing | EXISTS | `app/src/test/java/com/finapp/app/kyc/ProviderCallbackDatabaseTest.java#aLateCallbackIsEvidenceOnly` |
| T24 | FX provider evidence is append-only | EXISTS | `fx/src/test/java/com/finapp/fx/FxProviderEvidenceDatabaseTest.java#evidenceIsAppendOnly` |
| T24 | Backup and restore rehearsed, ending in the financial verification | PLANNED | `P15-TSK-030` |
| T25 | The outbox row commits and rolls back with the business transaction (`INV-EVT-01`) | EXISTS | `platform/src/test/java/com/finapp/platform/outbox/OutboxWriterTest.java#rollbackTakesTheOutboxRowWithIt`; `platform/src/test/java/com/finapp/platform/outbox/OutboxWriterTest.java#commitKeepsThemTogether` |
| T25 | Brokers across three zones with `min.insync.replicas` 2 | PLANNED | `P15-TSK-015` |
| T25 | Event freshness and consumption SLOs, and an outbox dead letter paging | PLANNED | `P15-TSK-025` |
| T25 | The broker outage procedure, rehearsed | PLANNED | `P15-TSK-032` |
| T26 | No record from an authenticated request is attributed to the platform, and every record carries the `INV-AUD-01` fields and joins to its request | EXISTS | `app/src/test/java/com/finapp/app/domain/AuditNamesTheActorDatabaseTest.java#authenticatedRequestsNameThePerson`; `app/src/test/java/com/finapp/app/domain/AuditNamesTheActorDatabaseTest.java#everyRecordCarriesTheAuditFields` |
| T26 | The auditable action registry and its catalogue agree | EXISTS | `app/src/test/java/com/finapp/app/audit/AuditableActionRegistryTest.java#theRegistryAndTheCatalogueAgree` |
| T26 | Audit retention and its archival design | PLANNED | `P15-TSK-029` |
| T27 | OCI revision labels, and build identity asserted after deploy | PLANNED | `P15-TSK-003` |
| T27 | Signatures with provenance and SBOM attestations, verified at admission | PLANNED | `P15-TSK-007` |
| T27 | A registry with immutable tags and scan on push | PLANNED | `P15-TSK-016` |
| T27 | Deploy by digest, with promotion refusing a tag or an unsigned digest | PLANNED | `P15-TSK-017` |
| T28 | A second approver on the production overlay, a promotion script demanding staging evidence, pull-based GitOps | PLANNED | `P15-TSK-017` |
| T28 | No standing human write access in production, and break-glass time-bound | PLANNED | `P15-TSK-010` |
| T29 | Read-only roles for humans, an audited, time-bound break-glass role, no `get secrets` | PLANNED | `P15-TSK-010` |
| T29 | A private cluster API endpoint, control-plane audit logs, separate production and non-production roots | PLANNED | `P15-TSK-014` |
| T29 | Workload identities, KMS key policies, separate accounts and keys per environment | PLANNED | `P15-TSK-016` |
| T29 | Break-glass and compromise procedures rehearsed | PLANNED | `P15-TSK-032` |
| T30 | CI's workflow token is read-only, and no job references a secret | EXISTS | `.github/workflows/ci.yml` |
| T30 | Pull-based GitOps: no CI credential reaches production | PLANNED | `P15-TSK-017` |
| T30 | No cloud credential in CI, and no state backend initialised by it | PLANNED | `P15-TSK-013` |
| T30 | Keyless signing from CI's identity, its trust narrowed at admission | PLANNED | `P15-TSK-007` |
| T31 | No handler is served without a declared rule, and every permission-guarded route requires exactly the permission the register names | EXISTS | `app/src/test/java/com/finapp/app/api/RoutePermissionRegisterTest.java#everyGuardedRouteRequiresItsRegisteredPermission`; `app/src/test/java/com/finapp/app/api/EveryEndpointDeclaresARuleTest.java#everyHandlerDeclaresSomething` |
| T31 | The public allow-list generated from the permission register, with an operator route refused on the public host | PLANNED | `P15-TSK-011` |
| T32 | Automated backups with point-in-time recovery under a separate KMS key, and deletion protection | PLANNED | `P15-TSK-015` |
| T32 | A restore rehearsed into a fresh database, ending in the financial verification, with the backup handled as a copy of the ledger | PLANNED | `P15-TSK-030` |
| T33 | The migrator role cannot create roles or databases, and is no superuser | EXISTS | `infra/postgres/initdb/00-roles.sql` |
| T33 | The journal's append-only trigger binds the migrator | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#theAppendOnlyTriggerBindsTheMigrator` |
| T33 | Only the migrator image holds the migrator's credentials, under the Job contract | PLANNED | `P15-TSK-004` |
| T33 | The migrator's egress reaches PostgreSQL alone | PLANNED | `P15-TSK-010` |
| T33 | The migrator's identity reads only its own secret | PLANNED | `P15-TSK-016` |
| T34 | Every ciphertext carries the version of the key that wrote it | EXISTS | `payments/src/test/java/com/finapp/payments/EvidenceCipherTest.java#roundTrips`; `kyc/src/test/java/com/finapp/kyc/DocumentCipherTest.java#roundTrips` |
| T34 | Another key never decrypts | EXISTS | `identity/src/test/java/com/finapp/identity/SecretCipherTest.java#anotherKeyCannotDecrypt` |
| T34 | Data keys wrapped by KMS keys with deletion waiting periods | PLANNED | `P15-TSK-016` |
| T34 | Key rings: one active version, the retained versions still decrypting | PLANNED | `P15-TSK-019` |
| T34 | Key rotation rehearsed | PLANNED | `P15-TSK-020` |
| T35 | Settlement file chunks and counterparty screening content are bound to their row, so a moved ciphertext fails | EXISTS | `settlement/src/test/java/com/finapp/settlement/SettlementFileCipherTest.java#theBindingHolds`; `kyc/src/test/java/com/finapp/kyc/CounterpartySubjectCipherTest.java#theRowIsBound` |
| T35 | Associated data bound in the ciphers that bind none | REMEDIATION | `X-TSK-010` |
| T36 | `verify-full` required to a database off loopback | EXISTS | `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#remoteWithoutTlsIsRefused`; `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#weakerModesAreRefused` |
| T36 | `rds.force_ssl` and private subnets | PLANNED | `P15-TSK-015` |
| T36 | Egress to PostgreSQL only from the application's and the migrator's pods | PLANNED | `P15-TSK-010` |
| T37 | Outbox and inbox retention sweeps, leaderless and batched, never a non-terminal row | PLANNED | `P15-TSK-029` |
| T38 | Every record from an authenticated request names the person who acted | EXISTS | `app/src/test/java/com/finapp/app/domain/AuditNamesTheActorDatabaseTest.java#authenticatedRequestsNameThePerson` |
| T38 | Operator sessions typed `OPERATOR`, and a refused step-up recorded | PLANNED | `P15-TSK-021` |
| T39 | An audited operator door to retry or discard an abandoned event (discard four-eyes), emitting the three platform actions | PLANNED | `P15-TSK-028` |
| T40 | The edge sets the client address, and only the edge's is trusted | PLANNED | `P15-TSK-011` |
| T40 | Per-source limits keyed on the address the edge sets | PLANNED | `P15-TSK-022` |
| T41 | A key reused with a different request is refused, so nothing is replayed to the wrong principal | EXISTS | `platform/src/test/java/com/finapp/platform/idempotency/IdempotentExecutorTest.java#differingFingerprintIsRejected` |
| T41 | Every idempotency claim scoped to its principal | REMEDIATION | `X-TSK-003` |
| T42 | A managed registry with immutable tags | PLANNED | `P15-TSK-016` |
| T43 | Provider answers enter through adapters, which translate them into the platform's own vocabulary (the decision) | EXISTS | `docs/adr/ADR-0008-provider-adapters.md` |
| T43 | The FX and credit providers' wire vocabularies are confined to their adapters by build rules, each with a planted leak caught | EXISTS | `app/src/test/java/com/finapp/app/architecture/FxProviderVocabularyIsConfinedTest.java#theVocabularyLivesInTheAdapterAlone`; `app/src/test/java/com/finapp/app/architecture/CreditProviderVocabularyIsConfinedTest.java#theVocabularyLivesInTheAdapterAlone` |
| T43 | The regulatory filing adapter: a malformed or oversized answer decides nothing | EXISTS | `accounting/src/test/java/com/finapp/accounting/RegulatoryFormTest.java#aMalformedAnswerDecidesNothing`; `accounting/src/test/java/com/finapp/accounting/RegulatoryFormTest.java#anOversizedAnswerDecidesNothing` |
| T43 | Static analysis over the adapters in CI | PLANNED | `P15-TSK-006` |
| T44 | Report generation reaches no writer, and no write path, by any route the build can see (`INV-ACC-14`) | EXISTS | `app/src/test/java/com/finapp/app/architecture/ReportingNeverWritesTest.java` |
| T44 | A `SELECT`-only reporting role for report generation (C13's cut candidate) | PLANNED | `P15-TSK-021` |

**Where the evidence is a database test.** Many `EXISTS` rows cite tests in the `database` tier. Phase 15's standing
deviation (C14) defers those tiers to one pass at the phase exit (`P15-TST-002`). Each such test exists and was green
at its own task's gate or at the last database pass. This register claims that the test exists and holds the stated
property. It does not claim a fresh run on this tree.

---

## 5. Residuals and gaps

What a threat's controls leave open, stated so it is not mistaken for coverage. Where no task owns a residual, §6 asks
the owner.

- **T15, the raw writer.** The database refuses an unbalanced entry, any edit of the journal and, after
  `P14-REM-004`, the Phase 14 shapes. But a **balanced, well-formed entry inserted by anyone holding `finapp_app`**
  passes every schema rule. Every module shares that one role, and the role holds `INSERT` on the journal. Such an
  entry is *detected*, not refused: by reconciliation against external evidence, by the product proofs, and by the
  continuous trial balance once `P15-TSK-025` pages on it. The controls of Phase 15 lower the likelihood: the
  credential is delivered only to the application (`-018`), and the database is reachable only from its pods (`-010`,
  `-015`). No task removes the `INSERT` path itself, for example through a definer-rights posting function as the only
  way in, or through a database role per module. **Owner item 2.**
- **T29, human administrative access.** The tasks bound what a human can do inside the cluster (`-010`) and log what
  the control plane does (`-014`). The identity controls of a real cloud account are built by no task: required MFA,
  workforce federation, and no long-lived access keys for humans. A real environment would set them, and §1.1 defers
  every real account. **Owner item 3.**
- **T28.** GitHub's branch protection and environment rules are repository settings. `P15-TSK-017` writes them down,
  and only the owner can apply them (§1.1). Until they are applied, "two persons on production" holds by convention
  and by `CODEOWNERS`, not by a setting that refuses. **Owner item 4.**
- **T4, T21, T27, T28, T29.** Today **no runtime control exists** for these threats, because nothing is deployed. Each
  threat is answered only by its planned tasks.
- **T9.** `KafkaTransportGuard` refuses only `PLAINTEXT` off loopback. **`SASL_PLAINTEXT`, authenticated but
  unencrypted, is admitted today.** `P15-TSK-026` widens the guard (routed on its entry). Not every consumer is a hint
  yet: the earliest consumers act on the payload, with the platform as actor (`SECURITY_ARCHITECTURE.md` §Who is
  acting). Lending's origination consumers are the ones evidenced as hints.
- **T7, T34, T35, the field-level ciphers.** Eight AES-256-GCM classes, built as nine instances over eight keys, stamp
  a key version on every row. **Each instance holds one key.** Only `CounterpartySubjectCipher` refuses a mismatched
  stamped version; the other seven fail at the authentication tag. Changing a key today makes every earlier row
  unreadable, which is why rotation waits for key rings (`-019`). **Three classes bind associated data**
  (`CreditEvidenceCipher`, `CounterpartySubjectCipher`, `SettlementFileCipher`). **Five bind none**: `SecretCipher`,
  `DocumentCipher`, `EvidenceCipher`, `PayoutEvidenceCipher` and `FxEvidenceCipher`. `X-TSK-010` names the first four,
  and the fifth is routed onto its entry. Every column outside these ciphers is plaintext in PostgreSQL until volume
  encryption exists (`-015`). `SECURITY_ARCHITECTURE.md` §Encryption at rest has the detail.
- **T2.** `/actuator/prometheus` and `/actuator/info` are served unauthenticated on the API port today. Two
  `CURRENT_STATE.md` debt rows record this, and `-008` and `-010` close them.
- **T38.** Today **every session actor is audited as `CUSTOMER`, operators included**, and a refused step-up writes no
  record. Both are debt rows that `-021` closes. The actor's identity is right; only its type is wrong.
- **T43.** No general bound on the size of a provider's answer exists across the adapters. The regulatory adapter is
  the one evidenced. The adapters call simulated providers in this programme, so this residual is not owned by any
  task. **Owner item 5.**
- **T31.** The build pins the permission of every route that declares one, and refuses a handler that declares no
  rule. **It does not assert that every route under `/v1/operator` is permission-guarded.** A route declaring only
  `@RequiresSession` would pass both guards. Today the operator surface shares the public port. `P15-TSK-011`'s
  allow-list, generated from the permission register, is the control that takes it off the internet.
- **T42.** A replica rescheduled while the registry is unreachable cannot pull its image. Nothing in Phase 15 caches
  or mirrors images. **Owner item 1.**
- **T44.** The reporting role is C13's first cut candidate. If it is cut, `-021`'s gate rewrites T44's planned row as
  a gap with an owner.
- **T13.** Time sync on nodes is a property of the managed cluster. No task asserts it, and the register does not
  claim it. The platform's answer is to decide financial instants on the database's clock.

---

## 6. Owner items

Only the owner can accept a risk. Each item below carries a recommendation. Nothing here blocks Phase 15.

1. **T42, a proposed acceptance (MEDIUM).** The registry is unavailable when a replica must start, so the rescheduled
   replica cannot pull its image. *Recommendation*: accept it for Phase 15 and re-own it to Phase 16, where registry
   high availability, image pre-pulling and disaster recovery belong. Record it as `ACCEPTED by the owner on <date>,
   until <Phase 16's exit>`.
2. **T15's residual.** A balanced entry inserted by a holder of the application's database credential is detected,
   not refused. *Recommendation*: record a post-Phase-15 hardening item, either a definer-rights posting function as
   the only `INSERT` path into the journal or a database role per module, measured against the posting hot path.
   Phase 15's credential and network controls stand meanwhile. Do not add it to Phase 15: it would change every
   module's write path, and Phase 15 leaves every posting path as Phases 3–14 built it (`PHASE_15_PLAN.md` §1).
3. **T29's residual.** The human identity controls of a real cloud account are built by no task. *Recommendation*:
   widen `P15-TSK-016` with a human IAM baseline in the `security` module (MFA required by policy, no long-lived human
   access keys, permission boundaries), asserted by `terraform test` with mock providers like the rest of the module.
   Otherwise, record it as a real-environment item under §1.1.
4. **T28's residual.** Branch protection and the production environment's required reviewers are the owner's to apply.
   *Recommendation*: apply them when the repository gains a production environment; `-017` writes them down.
5. **T43's residual.** No adapter bounds the size of a provider's answer in general. *Recommendation*: ask `-006`'s
   static analysis to flag an unbounded body read in an adapter, or record it for the first real provider integration.

---

## 7. Decided against

As `PHASE_15_PLAN.md` §9.3 decided, and kept here so that the model does not count these as missing controls:

- **mTLS inside the cluster, and a service mesh.** With one deployable there is no service-to-service hop. The hops
  that leave a pod (B4, B5, B6) each have their own TLS and authentication.
- **An API gateway product.** Authentication and authorisation are the application's (ADR-0030, ADR-0031). A gateway
  would be a second policy point that can disagree with the first. The edge does TLS, allow-lists and coarse limits.
- **OIDC for customers.** Customer identity is the platform's own product. Workforce federation waits for a workforce
  identity provider (C13).

---

## 8. What this register does not claim

- That a control is effective beyond what its evidence holds. A test proves its own assertions, and the control's
  text is written to them.
- That a `database`-tier test ran fresh on this tree (§4's note).
- That any planned control exists before its task's gate replaces the promise with evidence. The guard enforces that.
- That the platform is ready for production. That is `PRODUCTION_READINESS.md`'s question, and `P15-TSK-034` answers it.
- Compliance with, or certification under, any standard.
