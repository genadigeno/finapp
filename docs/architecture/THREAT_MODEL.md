# Threat Model

Written by `P15-TSK-001` (2026-11-08), Phase 15's first task and its security baseline. Every later Phase 15 task
builds a control this register names, and the register is held by `ThreatModelIsCompleteTest`
(`com.finapp.app.architecture`). The controls are decided in ADR-0120…ADR-0125 (`Proposed`) and in the ADRs each row
cites; this document decides nothing. It names threats, rates them by a written rule, and maps each one to a control
with evidence, to a task that will build one, to a gap with its owner, or to an acceptance that only the owner can
give.

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

**Today and target.** "Today" means the repository at this commit: the application, its guards and tests, the local
stack, CI. There is no manifest, edge, cloud account or deployed instance. Phase 15's merged tasks have built what the
repository can hold - the application image and the migrator image (built, compared and asserted locally and in CI's
image jobs, pushed nowhere), and controls in code and configuration - each on its own row below. *(Read "Phase 15 has built nothing yet
... There is no image, manifest, edge, cloud account or deployed instance" until 2026-11-08, when a review found the
opening sentence stale beside the image note that followed it.)*
The edge and network threats are therefore answered by the Phase 15 tasks that will build those things, and the
register says so row by row. Where evidence holds only the local stack or CI, the row says so, and the deployed
equivalent is its own planned row.

### 1.2 STRIDE

Each boundary in §2 answers all six categories: **S**poofing (a party or system claiming to be another),
**T**ampering (an unauthorised change to data, code or configuration), **R**epudiation (an act that cannot be tied to
its actor, or whose record can be lost), **I**nformation disclosure, **D**enial of service, and **E**levation of
privilege. A category answers with the threats that apply at that boundary today, or with `N/A` and the reason none
does.

### 1.3 The rating rule

A threat is rated **inherently**: as the platform would be deployed under C1–C15 *before* any control specific to
that threat. Ratings therefore do not fall as controls land; the disposition carries the controls. **Likelihood** is
who can attempt the threat, or how often it arises unprompted:

- **HIGH**: anyone who can reach a public route, or a condition that every release, retry, rebalance or provider
  interaction produces.
- **MEDIUM**: needs one foothold (a stolen credential, an insider, a compromised dependency, provider or pod, a
  position inside the private network, a defect in the platform's own code, an operator's mistake), or an
  infrastructure event that happens in normal operation but not on every request (a clock step, a node loss, a broker
  outage).
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

- **`CONTROL`**: one or more rows in §4 answer it, at least one of them not a `GAP`. A threat may carry a residual
  risk beside its controls (§5).
- **`ACCEPTED by the owner on <YYYY-MM-DD>`**, optionally followed by `, until <YYYY-MM-DD>`: the owner accepted the
  risk on that date. **Only the owner accepts a risk**, and the guard refuses any other name. No task records an
  acceptance on the owner's behalf, and an acceptance past its `until` date turns the guard red. A scan finding
  accepted under `PHASE_GATES.md` §5 Phase 15 criterion 2 carries an `until`.
- **`PROPOSED ACCEPTANCE`**: a task recommends acceptance and the owner has not decided. Never allowed for a CRITICAL
  or HIGH threat, and never allowed at any rating once Phase 15 is complete: by the exit the owner has decided.

A row in §4 has one of four states, and its evidence column must match its state:

- **`EXISTS`**: the control is built. The evidence is one or more repository paths in backticks, each a file that
  exists. `Class.java#member` names the test that holds the claim, wherever the class is broader than the claim; the
  member must be a test declared in that file (a method annotated `@Test`, `@ParameterizedTest` or alike, or an
  `@ArchTest` rule), never a helper; a whole test file cited without a member must declare at least one test. **A
  file that is not a test counts for a CRITICAL or HIGH threat only with a content pin**: `` `contains: <text>` `` or
  `` `lacks: <text>` `` right after the path, which the guard checks against the file. Otherwise a test asserts the
  property, and the row cites the test. **A document under `docs/` never counts for a CRITICAL or HIGH threat**,
  pinned or not: it states a control and builds none. A rehearsal record (`-032`) counts only once a gate widens this
  grammar deliberately, naming the records' directory and form.
- **`PLANNED`**: a Phase 15 task builds it. The evidence is the task's id.
- **`REMEDIATION`**: an earlier remediation entry (`P14-REM-*`) or a cross-cutting item (`X-TSK-*`) builds it, outside
  the Phase 15 milestones. The evidence is the entry's id. This is the honest answer where that entry is the only owner.
- **`GAP`**: nothing builds it, and the row says so. The evidence is the postponed issue (`PI-NNN`) or the owner item
  of §6 (`owner item N`) that holds it, and `owner: <who>, since <YYYY-MM-DD>`. A `GAP` row is never a control: it is
  the place a cut, postponed or unowned promise goes, so the register keeps it rather than deleting it.

### 1.5 Keeping it true

`ThreatModelIsCompleteTest` fails the build when:

- a line of §2, §3 or §4 that looks like a table row (its first character, spaces aside, is a pipe) does not parse,
  indented or malformed rows included;
- one of the ten boundaries of §2 is missing or renamed, or any boundary leaves a STRIDE category unanswered;
- the matrix of §2 and the register of §3 disagree about which threat sits at which boundary and category;
- a rating is not the cell of §1.3's table, or the table itself has changed;
- a threat has no disposition, a `CONTROL` threat has no row that is not a `GAP`, an acceptance names anyone but the
  owner or is past its `until`, or a CRITICAL or HIGH threat is neither controlled nor accepted by the owner;
- an `EXISTS` row names a file that does not exist, a `#member` that is not a test declared in its file, a whole test
  file that declares no test, a pin that does not hold, or, for a CRITICAL or HIGH threat, a document under `docs/` or
  a bare non-test file; a `PLANNED` or `REMEDIATION` row names an id
  that is not a backlog entry; a `GAP` row names no postponed issue or owner item, or no owner and date; or any task id
  in this document, short (`-018`) or long, is not a backlog entry;
- **a `PLANNED` or `REMEDIATION` row names a task that is no longer live** (`PLANNED`, `READY` or `IN_PROGRESS`). When
  a task's status becomes `COMPLETE`, its gate must replace the promise with the evidence it built. When a task is
  postponed or a promise is cut, the row becomes a `GAP` naming what holds it. A row naming two tasks goes red when
  either one finishes, so the gate splits it. **The same holds anywhere in this document**: a task id in a residual,
  an owner item or a note (short or long) goes red, naming the task and the section, once the task is no longer live,
  so the gate rewrites the sentence. The one exception is `P15-TSK-001`, cited as this register's author;
- §3's summary line does not count what the register holds;
- **once Phase 15's status in `CURRENT_STATE.md` is complete**, a CRITICAL or HIGH threat has no `EXISTS` row and is
  not accepted by the owner, or any threat's acceptance is still only proposed. This is `PHASE_GATES.md` §5 Phase 15
  criterion 1 as written: at the exit, a control counts only when its evidence exists;
- it parses fewer boundaries, cells, threats or rows than its structural floors, or a threat not accepted by the
  owner has no row at all, so a renamed heading or a broken table cannot pass as an empty register.

Add a threat at the end, with the next free number. Never renumber or delete a threat: other documents and tasks cite
these numbers. **Rows are independent of one another.** When two branches both edit this document, keep both sides
of a conflict, re-count the summary line, and run the guard: it says what the merge broke.

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
| Information disclosure | T2, T21, T31, T47 | |
| Denial of service | T1 | |
| Elevation of privilege | T31 | |

### B2 — Edge → application

The edge forwards to the `finapp-app` Service, which reaches any of N replicas. Everything past this boundary is the
application's: it authenticates (opaque sessions, merchant API keys, checkout tokens, callback signatures), authorises
(permissions and ownership), and decides. Every replica must reach the same decision about the same request, so every
arbitration lives in PostgreSQL. Every statement the application sends PostgreSQL is hand-written JDBC.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T20, T40 | |
| Tampering | T16 | A retried, duplicated or replayed command, which may land on two replicas at once |
| Repudiation | T26 | |
| Information disclosure | T2, T19, T46 | |
| Denial of service | T1, T14, T41 | |
| Elevation of privilege | T4, T19, T45 | |

### B3 — Operator network → operator ingress

Operators (ledger, reconciliation, risk, accounting, lending and BNPL staff) reach the routes an operator permission
guards: `/v1/operator/**` and the operator doors elsewhere. In the target, these routes are served only by the
internal ingress (C9, `P15-TSK-011`). **Today they share the application's single port with every other route**, and
the cells below answer for today.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T3 | |
| Tampering | T18 | |
| Repudiation | T38 | |
| Information disclosure | T3 | |
| Denial of service | T14 | An operator's reports, investigations and censuses are the platform's heaviest reads, and today anyone can reach the routes that start them |
| Elevation of privilege | T3, T18 | |

### B4 — Application → PostgreSQL

Every replica holds a pool of connections as `finapp_app`, and the migrator Job connects as `finapp_migrator`. This is
the boundary to the financial record: the journal, the audit record and the evidence. The database's own rules
(grants, `CHECK`s, triggers) are the controls that bind every writer, including one that bypasses the domain.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T36 | |
| Tampering | T13, T15, T35, T45 | |
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
builds, tests and scans, builds the application image (since 2026-11-08), and pushes nothing.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T27 | |
| Tampering | T5, T27 | |
| Repudiation | T27 | |
| Information disclosure | T6 | |
| Denial of service | N/A | CI or the registry being unavailable delays a build or a push and stops nothing that runs. The pull a replica needs when it starts is the cluster's, modelled at B9 (T42) |
| Elevation of privilege | T5, T30 | |

### B9 — GitOps → cluster

The GitOps controller applies the environment's overlay, pinned by digest, to the cluster. It runs the migrator Job
before the rollout, then rolls the N replicas (C8). The cluster pulls each replica's image from the registry.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T27 | |
| Tampering | T11, T12, T28 | |
| Repudiation | T28 | |
| Information disclosure | T6 | |
| Denial of service | T12, T42 | |
| Elevation of privilege | T28, T33 | |

### B10 — Humans → cluster and cloud

Engineers and operators reaching the cluster's API, the cloud account, the secret store, the key service, the
database's administrative access, the backups and the telemetry back ends.

| Category | Threats | Note |
|---|---|---|
| Spoofing | T29 | |
| Tampering | T11, T39 | |
| Repudiation | T10, T39 | |
| Information disclosure | T6, T32, T34, T46 | |
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
| T10 | Audit tampering or loss | B4:R, B10:R | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T11 | Configuration drift and unreviewed manual change | B9:T, B10:T | MEDIUM | HIGH | HIGH | CONTROL |
| T12 | A deployment breaking financial consistency or availability: version skew across replicas, a migration, an event or handler change | B5:T, B9:T, B9:D | HIGH | SEVERE | CRITICAL | CONTROL |
| T13 | A stepped instance clock deciding a financial instant | B4:T | MEDIUM | HIGH | HIGH | CONTROL |
| T14 | Resource exhaustion and noisy neighbours, the database connection budget above all | B2:D, B3:D, B4:D | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T15 | A raw writer: a write reaching financial tables outside the ledger's doors, from a code defect or from anyone holding the application's database credential | B4:T, B4:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T16 | A retried, duplicated or replayed command producing a second financial effect, including two copies landing on two replicas at once | B2:T | HIGH | SEVERE | CRITICAL | CONTROL |
| T17 | A duplicated, redelivered or replayed event producing a second effect | B5:T | HIGH | SEVERE | CRITICAL | CONTROL |
| T18 | An operator acting alone where four eyes are required | B3:T, B3:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T19 | Broken object-level authorisation: a customer or merchant reading or acting on another party's resource, and so on every party's | B2:I, B2:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T20 | A stolen bearer credential (session token, merchant API key, checkout token) replayed | B1:S, B2:S | MEDIUM | HIGH | HIGH | CONTROL |
| T21 | Client traffic intercepted or altered in transit to the edge | B1:T, B1:I | MEDIUM | HIGH | HIGH | CONTROL |
| T22 | Impersonation or interception on the outbound provider hop, which carries the platform's own provider credentials | B6:S, B6:T, B6:I | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T23 | A provider unavailable, slow or answering an unknown state, and the outcome mishandled into a duplicate or a lost movement | B6:D | HIGH | SEVERE | CRITICAL | CONTROL |
| T24 | A provider's answer or callback disputed after its evidence was lost | B6:R, B7:R | MEDIUM | HIGH | HIGH | CONTROL |
| T25 | Kafka unavailable or lagging | B5:D | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T26 | A customer or merchant repudiating an act | B2:R | MEDIUM | HIGH | HIGH | CONTROL |
| T27 | An image substituted between build and run, or an image without provenance admitted | B8:S, B8:T, B8:R, B9:S | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T28 | An unreviewed or single-person change reaching production | B9:T, B9:R, B9:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T29 | A human's cluster or cloud administrative credential compromised or abused | B10:S, B10:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T30 | CI as a path to production: a workflow or a CI credential reaching an environment | B8:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T31 | The operator surface reachable from the internet | B1:I, B1:E | HIGH | HIGH | HIGH | CONTROL |
| T32 | A backup disclosed, or not restorable when needed | B10:I, B10:D | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T33 | The migrator's credential or Job misused to alter the schema or financial data | B4:E, B9:E | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T34 | A field-level key version leaked, or lost so that evidence under it cannot be read | B10:I, B10:D | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T35 | A ciphertext moved to another row and decrypted as that row's evidence | B4:T | MEDIUM | HIGH | HIGH | CONTROL |
| T36 | Impersonation or interception of the database hop | B4:S, B4:I | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T37 | Unbounded growth of operational tables (outbox, inbox, audit) | B4:D | HIGH | MEDIUM | MEDIUM | CONTROL |
| T38 | An operator's act recorded under a customer's actor type, or a refused privileged attempt left unrecorded | B3:R | HIGH | HIGH | HIGH | CONTROL |
| T39 | A dead-lettered event resolved by an unaudited manual change | B10:T, B10:R | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T40 | A client's source address spoofed through forwarded headers, to evade a per-source limit or to mislead a record | B2:S | MEDIUM | MEDIUM | MEDIUM | CONTROL |
| T41 | One client's idempotency key value refusing another client's request | B2:D | LOW | LOW | LOW | CONTROL |
| T42 | The registry unavailable when a replica must start | B9:D | MEDIUM | MEDIUM | MEDIUM | ACCEPTED by the owner on 2026-11-08, until 2027-06-30 |
| T43 | A hostile or malformed provider answer driving an adapter outside its contract | B6:E | MEDIUM | HIGH | HIGH | CONTROL |
| T44 | A report path able to write | B4:E | MEDIUM | HIGH | HIGH | CONTROL |
| T45 | Injection through hand-written JDBC: a request value reaching a statement's text rather than a bind parameter, so a caller runs SQL as the application's role | B2:E, B4:T | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T46 | Restricted data or a secret leaking through logs, spans, metric labels, error bodies or the telemetry back ends, which have their own readers and retention | B2:I, B10:I | MEDIUM | SEVERE | CRITICAL | CONTROL |
| T47 | Login identifier enumeration through registration, which answers a taken identifier distinctly by design | B1:I | HIGH | MEDIUM | MEDIUM | ACCEPTED by the owner on 2026-11-08 |

**Summary.** 47 threats: 25 CRITICAL, 13 HIGH, 8 MEDIUM, 1 LOW. 45 are `CONTROL`. Two, T42 and T47 (both MEDIUM),
are accepted by the owner (2026-11-08; T42 until 2027-06-30; §6 items 1 and 6). None is `PROPOSED ACCEPTANCE`.
*(Read "are `PROPOSED ACCEPTANCE` awaiting the owner (§6). No threat is `ACCEPTED`." until the owner's decisions of
2026-11-08.)*

*(The fix round of 2026-11-08 re-read every rating against §1.3's words. T10, T19, T22, T32, T33, T34 and T36 rose to
CRITICAL; T35, T38, T43 and T44 to HIGH. Each had understated a likelihood the definitions call one foothold, or an
impact they call SEVERE. T42 moved from B8 to B9, because the image pull is the cluster's. T45, T46 and T47 were
added.)*

---

## 4. Controls and evidence

One row per control claim. The claim says exactly what the evidence holds, and no more.

| Threat | Control | State | Evidence |
|---|---|---|---|
| T1 | Failed authentications counted per identity in PostgreSQL. Crossing the threshold locks the identity, and ten instances failing concurrently count every failure | EXISTS | `app/src/test/java/com/finapp/app/domain/AuthenticationLockoutDatabaseTest.java#crossingTheThresholdLocks`; `app/src/test/java/com/finapp/app/domain/AuthenticationLockoutDatabaseTest.java#theCounterIsNotBypassableByConcurrency` |
| T1 | Authentication's five failing paths each perform exactly one credential derivation, so a login guess's cost does not reveal which identifiers exist (`INV-IDN-07`). Registration does reveal it, by design: T47 | EXISTS | `app/src/test/java/com/finapp/app/domain/AuthenticationCostsTheSameDatabaseTest.java#everyFailingPathDoesTheWork` |
| T1 | A request body over the size limit is refused `413`, whether or not its length is declared | EXISTS | `app/src/test/java/com/finapp/app/api/RequestValidationTest.java#anOversizedBodyIsRefused`; `app/src/test/java/com/finapp/app/api/RequestValidationTest.java#aChunkedOversizedBodyIsRefused` |
| T1 | Coarse per-source limits at the public ingress | PLANNED | `P15-TSK-011` |
| T1 | A cluster-safe registration throttle and per-source limits on the unauthenticated routes, arbitrated in PostgreSQL | PLANNED | `P15-TSK-022` |
| T2 | The actuator is an allow-list: `env`, `beans`, `configprops`, `threaddump`, `loggers`, `mappings`, `metrics`, `conditions` and `scheduledtasks` answer `404` | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#theEndpointAllowListHolds` |
| T2 | `heapdump`, `shutdown` and `caches` answer `404` | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#theEndpointsBehindASecondGateAreAlsoAbsent` |
| T2 | A health response is a status and nothing else, and `info` carries build identity and nothing about the machine | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#healthPublishesStatusAndNothingElse`; `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#infoCarriesBuildIdentityOnly` |
| T2 | The actuator answers on the management port alone: health, liveness, readiness, info and the scrape answer there, and the API port answers `404` for each, on production's own configuration | EXISTS | `app/src/test/java/com/finapp/app/api/ManagementPortTest.java#theActuatorAnswersOnlyOnTheManagementPort` |
| T2 | The management port binds loopback unless a deployment names an address: production's file states 8081 and `${FINAPP_MANAGEMENT_ADDRESS:127.0.0.1}`, the running management connector is bound to a loopback address, and, on a host with another address, the port is refused there while the API port accepts | EXISTS | `app/src/test/java/com/finapp/app/api/ManagementPortTest.java#theManagementPortBindsLoopback`; `app/src/test/java/com/finapp/app/api/ManagementPortTest.java#productionStatesTheContract` |
| T2 | An actuator that would answer on every interface refuses startup, before any port opens: on production's own file an empty or blank `FINAPP_MANAGEMENT_ADDRESS` (which `${FINAPP_MANAGEMENT_ADDRESS:127.0.0.1}` does not default) and `0.0.0.0`, judged with every other spelling of every interface on the address it parses to, and a management port unset or the API's own | EXISTS | `app/src/test/java/com/finapp/app/security/ManagementAddressGuardTest.java#anEmptyVariableRefusesStartup`; `app/src/test/java/com/finapp/app/security/ManagementAddressGuardTest.java#aBlankVariableAndEveryInterfaceRefuseStartup`; `app/src/test/java/com/finapp/app/security/ManagementAddressGuardTest.java#anyLocalIsRefused`; `app/src/test/java/com/finapp/app/security/ManagementAddressGuardTest.java#anEmptyManagementPortRefusesStartup` |
| T2 | In Kubernetes the management port binds the pod's own address, from the downward API, and no Service exposes it: the base states both, and CI's manifest check refuses a rendered overlay that names another source or routes a Service to 8081, each refusal proven by a planted violation in the same run | EXISTS | `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#theManagementPortIsOnThePodAndOnNoService`; `infra/scripts/manifest-check.sh` `contains: refused management-address management-address` `contains: refused management-address-literal management-address` `contains: refused service-management service-api-only` `contains: refused service-extra-port service-api-only` |
| T2 | NetworkPolicy admitting the management port from the kubelet and monitoring only | PLANNED | `P15-TSK-010` |
| T3 | Below `MULTI_FACTOR`, the interceptor refuses the manual adjustment's and the prior-period adjustment's approvals `403` before the permission is read | EXISTS | `app/src/test/java/com/finapp/app/ledger/ApprovalAssuranceTest.java#theRefusalIsTheInterceptors` |
| T3 | The period, rebuild and valuation-gap approvals are pinned at `MULTI_FACTOR` | EXISTS | `app/src/test/java/com/finapp/app/ledger/ApprovalAssuranceTest.java#thePeriodApprovalsArePinned`; `app/src/test/java/com/finapp/app/ledger/ApprovalAssuranceTest.java#theRebuildAndTheGapApprovalsStepUp` |
| T3 | MFA cannot be bypassed: elevating one session elevates none of the identity's others, using a session never raises its assurance, and the only endpoint that issues a session demands a verified code (`INV-IDN-05`) | EXISTS | `app/src/test/java/com/finapp/app/domain/MfaCannotBeBypassedDatabaseTest.java#anOlderPasswordSessionIsNotElevated`; `app/src/test/java/com/finapp/app/domain/MfaCannotBeBypassedDatabaseTest.java#aRefreshDoesNotRaiseAssurance`; `app/src/test/java/com/finapp/app/domain/MfaCannotBeBypassedDatabaseTest.java#theSessionIssuingEndpointDemandsAFactor` |
| T3 | No production path creates or elevates a session without being enumerated | EXISTS | `app/src/test/java/com/finapp/app/architecture/MfaBypassPathsAreEnumeratedTest.java#everyPathIsEnumerated` |
| T3 | Each role's permissions are code, and each role's exact grants are pinned | EXISTS | `identity/src/test/java/com/finapp/identity/RoleNameTest.java` |
| T3 | Every permission-guarded route requires exactly the permission the register names | EXISTS | `app/src/test/java/com/finapp/app/api/RoutePermissionRegisterTest.java#everyGuardedRouteRequiresItsRegisteredPermission` |
| T3 | A privileged endpoint refuses a session whose identity holds no role, and the denial is audited; a revoked role stops working on the next request | EXISTS | `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#negativeAuthorizationForPrivilegedEndpoints`; `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#aDenialIsAudited`; `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#roleRevocationIsImmediate` |
| T3 | An operator's read of a KYC document's content, and of a settlement file's raw content, is audited: who looked, and for the settlement file a required reason | EXISTS | `app/src/test/java/com/finapp/app/kyc/DocumentUploadDatabaseTest.java#theReadPathDecryptsVerifiesAndAudits`; `app/src/test/java/com/finapp/app/settlement/SettlementRoutesDatabaseTest.java#contentReadsAreReasonedAuditedAndByteIdentical` |
| T3 | Operator routes served only on the internal ingress | PLANNED | `P15-TSK-011` |
| T3 | An operator act is recorded in the operator population (`EMPLOYEE`): the interceptor types the request from the identity's live roles, read once with the session, on a route declaring a permission; a customer refused at an operator route is recorded `CUSTOMER`, and a role holder on their own account acts as a customer | EXISTS | `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#anOperatorActIsTypedEmployee`; `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#aRefusalIsTypedByWhoTried`; `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#aRoleHolderOnACustomerRouteIsACustomer` |
| T3 | A refused step-up is recorded once, in a transaction of its own committed before the refusal is answered: the interceptor's at a declared step-up, the error contract's at an operation's own; on either path a record that cannot be written answers `500`, never a `403` without its record | EXISTS | `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#aRefusedStepUpIsRecorded`; `app/src/test/java/com/finapp/app/session/StepUpRefusalsTest.java#anOperationsRefusalIsRecordedOnce`; `app/src/test/java/com/finapp/app/session/StepUpRefusalsTest.java#aRefusalWhoseRecordFailsIsAnsweredAsAFailure` |
| T3 | The privileged access review's role register and propose/approve partition are the code's, role by role and pair by pair | EXISTS | `app/src/test/java/com/finapp/app/architecture/PrivilegedAccessReviewTest.java#theRoleRegisterIsTheCodes`; `app/src/test/java/com/finapp/app/architecture/PrivilegedAccessReviewTest.java#thePartitionIsTheCodes` |
| T3 | Break-glass and compromise procedures, audited and rehearsed | PLANNED | `P15-TSK-032` |
| T4 | The application image's runtime stage is distroless's `nonroot` Java pinned by digest, declares `USER 65532:65532` explicitly and numerically, and adds the boot jar's four layers and nothing else: no `RUN`, `ADD`, `ENV`, `HEALTHCHECK` or `--chown` | EXISTS | `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theRuntimeIsDistrolessNonrootOnTheToolchainsJava`; `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theRuntimeStageRunsAsUid65532`; `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theRuntimeStageAddsTheJarsFourLayersAndNothingElse` |
| T4 | On the built image, CI's image job refuses a user other than `65532:65532`, a file named for a shell or a package manager anywhere, a program on the `PATH` other than `java`, and an entry that is neither the pinned base's nor the boot jar's; run with a read-only root and every capability dropped, the image must report ready with every process at UID 65532 | EXISTS | `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theImageIsBuiltComparedAndRunInCiAndItsBasesProposedWeekly`; `infra/scripts/image-build.sh` `contains: [ "$user" = "$RUNTIME_USER" ]` `contains: the image holds a shell or a package manager` `contains: [ "$programs" = "usr/bin/java " ]` `contains: outside /app the image differs from the pinned runtime base`; `infra/scripts/image-readiness.sh` `contains: --read-only --tmpfs /tmp --cap-drop ALL` `contains: [ "$uids" = "$RUNTIME_UID " ]` |
| T4 | The restricted pod profile on every committed workload, the application, the migrator Job and the test-only copies alike: non-root by number, a read-only root, every capability dropped, no privilege escalation, a seccomp profile, no service-account token offered or mounted. CI's manifest check refuses a rendered overlay lacking any of these, a key stated with no value counting as lacking it, on any workload kind and on an init container, each refusal proven by a planted violation in the same run. This is a check of what is committed: admission's refusal at the cluster is the row below | EXISTS | `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#theApplicationRunsTheRestrictedProfile`; `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#everyOtherWorkloadRunsItToo`; `infra/scripts/manifest-check.sh` `contains: refused read-only security-read-only` `contains: refused capability-kept security-capabilities` `contains: refused escalation security-escalation` `contains: refused automount-on-pod security-token` `contains: refused seccomp-unconfined security-seccomp` `contains: refused init-container security-read-only` `contains: refused job-read-only security-read-only` `contains: refused null-security-context security-read-only` `contains: refused null-init-containers security-read-only` |
| T4 | Default-deny NetworkPolicy with an egress allow-list; Pod Security Admission `restricted`; no Kubernetes RBAC for the workload | PLANNED | `P15-TSK-010` |
| T4 | A workload identity that reads only its own secrets | PLANNED | `P15-TSK-016` |
| T4 | A pod cannot take its node's credentials: every node launches from a template requiring IMDSv2 with a hop limit of one, and no node accepts SSH. Asserted over a plan against a mock provider, never applied | EXISTS | `infra/terraform/modules/kubernetes/tests/kubernetes.tftest.hcl` `contains: A pod can reach the instance metadata service and so the node's credentials.` `contains: A node accepts SSH.`; `infra/terraform/modules/kubernetes/main.tf` `contains: http_put_response_hop_limit = 1` |
| T4 | The cluster would enforce a NetworkPolicy: its VPC CNI add-on turns on the network policy agent, which is off by default. Asserted over a plan against a mock provider, never applied; the policies themselves are the default-deny row above | EXISTS | `infra/terraform/modules/kubernetes/tests/kubernetes.tftest.hcl` `contains: NetworkPolicy would be accepted and enforced by nothing`; `infra/terraform/modules/kubernetes/main.tf` `contains: enableNetworkPolicy = "true"` |
| T5 | Every resolved dependency verified against a committed checksum. Signature verification is off: trust on first use, stated | EXISTS | `gradle/verification-metadata.xml` `contains: <verify-metadata>true</verify-metadata>` `contains: <verify-signatures>false</verify-signatures>` |
| T5 | Every action in CI's one workflow pinned to a full commit SHA, with weekly update proposals for the pins | EXISTS | `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#everyActionIsPinnedToACommit` |
| T5 | Every scanner image pinned by a full sha256 digest, and the pins' freshness checked by a scheduled job | EXISTS | `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#everyScannerImageIsPinnedByDigest` |
| T5 | The build's CycloneDX SBOM generated and scanned for known vulnerabilities in CI | EXISTS | `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#theSbomIsScanned` |
| T5 | The application image's two base images pinned as `tag@sha256` in the `Dockerfile`'s `FROM` lines, their one definition, in a two-stage build whose runtime stage holds no build tool; an unpinned base in any root Dockerfile fails the build, and the image job refuses one before building | EXISTS | `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#everyBaseIsPinnedByDigest`; `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theRuntimeStageAddsTheJarsFourLayersAndNothingElse`; `infra/scripts/check-pinned-images.sh` `contains: is not pinned as repository:tag@sha256:<digest>`; `infra/scripts/image-build.sh` `contains: check-pinned-images.sh" --offline` |
| T5 | Dependabot proposes the base images' next tag and digest weekly, and the scheduled pin check reports a base whose tag moved or that has a newer release | EXISTS | `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theImageIsBuiltComparedAndRunInCiAndItsBasesProposedWeekly`; `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#everyScannerImageIsPinnedByDigest`; `infra/scripts/check-pinned-images.sh` `contains: check "base $repository"` |
| T5 | Two builds of one commit compared in CI's image job: the boot jar, built from source both times (every task re-run), must have the same sha256, and the two images the same image id and the same layer digests, one by one | EXISTS | `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theImageIsBuiltComparedAndRunInCiAndItsBasesProposedWeekly`; `infra/scripts/image-build.sh` `contains: --rerun-tasks :app:bootJar` `contains: the boot jar is not reproducible` `contains: the two builds of $REVISION differ` |
| T5 | Both images scanned in CI, each in the job that builds it: the application image in the `image` job (every local `finapp-*:<commit>`, the application image required) and the migrator image in the `migrator-image` job (named with `--only finapp-migrator`, required), for vulnerabilities, secrets and misconfiguration, HIGH and CRITICAL failing, read from a saved tar so the scanner holds no Docker socket | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#everyImageIsScannedAtTheBar`; `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#ciScansBothImagesAndKeepsTheirSboms`; `infra/scripts/image-scan.sh` `contains: --severity "$SEVERITY" --exit-code "$FOUND"` `lacks: docker.sock` |
| T5 | Five plants refused by name in every run of that scan, in both jobs, through the same flags and the same list of carried findings as the real images: a vulnerable base, a library whose only finding is HIGH (so the bar lowered to CRITICAL fails the job), a secret in a layer, a secret in the environment, an image running as root | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#everyImageIsScannedAtTheBar`; `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#theRulesRefuseTheirPlants`; `infra/scripts/image-scan.sh` `contains: refuses high-only "CVE-2021-23337"` |
| T5 | One CycloneDX SBOM for each of the two images, kept with its job's run, the build failing if it does not list the image's operating-system packages and every third-party jar in the image by path, or lists a test-scope library | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#everySbomDescribesItsImage`; `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#ciScansBothImagesAndKeepsTheirSboms`; `infra/scripts/image-scan.sh` `contains: omits third-party jars the image holds` |
| T5 | Each image's last build rescanned weekly against a fresh database: the workflow's schedule runs both image jobs, each of which rebuilds the last commit and refuses two builds of one commit that differ; the databases are never cached between runs | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#ciScansBothImagesAndKeepsTheirSboms`; `infra/scripts/image-scan.sh` `contains: --download-db-only` |
| T5 | Static analysis of every module's production source in CI: Semgrep pinned by digest with no network, rules vendored, a finding needing a baseline row with a reason and an expiry, the scanner required to have read every Java file, the rules' own tests and the plants run in the same job | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#theStaticAnalysisRunsOverTheVendoredRulesOffline`; `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#theBaselineIsSmallAndReasoned`; `infra/scripts/sast.sh` `contains: --network none` |
| T5 | The findings the image and dependency scans carry today (18 HIGH, 0 CRITICAL: ten in the distroless base's packages, which both images share, eight in the application's libraries), each with a reason and an expiry, applied by `infra/trivy/accepted-findings.yaml`; accepted by the owner on 2026-11-09, each until its date in §6 item 12 | GAP | `owner item 12`; owner: the owner, since 2026-11-08 |
| T5 | Signatures and provenance, with admission refusing an unsigned image | PLANNED | `P15-TSK-007` |
| T6 | No credential literal in committed configuration except the one marked local default | EXISTS | `app/src/test/java/com/finapp/app/security/CommittedConfigurationHoldsNoSecretTest.java#noCommittedConfigurationHoldsACredentialLiteral` |
| T6 | The whole of git history scanned for secrets in CI, with the pinned scanner | EXISTS | `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#theHistoryIsScannedForSecrets` |
| T6 | A field or accessor named for a secret must be held in `Sensitive` (the build rule) | EXISTS | `app/src/test/java/com/finapp/app/architecture/NoUnwrappedSecretRulesTest.java#secretsAreWrapped` |
| T6 | A credential held in `Sensitive` is masked through interpolation, concatenation and `toString`, on the console and on the file appender | EXISTS | `app/src/test/java/com/finapp/app/observability/LogRedactionTest.java#everyRenderingPathIsMasked`; `app/src/test/java/com/finapp/app/observability/RedactionAcrossAppendersTest.java#redactionHoldsOnBothAppenders` |
| T6 | The marked local database credential is refused against a database not on loopback; each confined credential's variable is the one its configuration binds | EXISTS | `app/src/test/java/com/finapp/app/security/DatabaseCredentialGuardTest.java#remoteIsRefused`; `app/src/test/java/com/finapp/app/security/ConfinedCredentialVariablesTest.java#theNamedVariableIsTheOneThatBinds` |
| T6 | A URL the database driver reads as another host - a `host`, `PGHOST` or `service` query parameter, an unbracketed IPv6 entry, a second authority - is not read as loopback, so the marked local credential is refused against it (measured against the pinned driver) | EXISTS | `app/src/test/java/com/finapp/app/security/DatabaseCredentialGuardTest.java#aHostTheDriverReadsElsewhereIsRefused` |
| T6 | No heap dump is served | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#theEndpointsBehindASecondGateAreAlsoAbsent` |
| T6 | Every secret inventoried and delivered from the secret store, with a committed `kind: Secret` carrying data refused | PLANNED | `P15-TSK-018` |
| T6 | Kubernetes Secrets are envelope-encrypted in etcd under a customer-managed KMS key each environment must supply: the cluster's `encryption_config` names `secrets` and that key, and the input refuses an alias and a malformed value, and at each root another account's or region's key. Asserted over a plan against a mock provider, never applied; CI's check refuses a plant that removes the block | EXISTS | `infra/terraform/modules/kubernetes/tests/kubernetes.tftest.hcl` `contains: Kubernetes Secrets are not encrypted in etcd.` `contains: Secrets in etcd are not encrypted under the key this module is given.` `contains: an_alias_is_refused_for_the_etcd_key`; `infra/terraform/modules/kubernetes/main.tf` `contains: resources = ["secrets"]`; `infra/terraform/environments/staging/tests/staging.tftest.hcl` `contains: a_key_of_another_account_is_refused` `contains: a_key_of_another_region_is_refused`; `infra/terraform/environments/production/tests/production.tftest.hcl` `contains: a_key_of_another_account_is_refused` `contains: a_key_of_another_region_is_refused`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#ciRunsEveryCheckOverTheTreeAndItsPlants` |
| T6 | Each workload's identity reads only its own secrets, under KMS keys per environment and purpose | PLANNED | `P15-TSK-016` |
| T6 | Humans denied `get secrets` | PLANNED | `P15-TSK-010` |
| T6 | Both images scanned for secrets in their layers and in their own configuration (`ENV` and history), with a generated secret planted in each of those two places and refused by name in the same run | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#everyImageIsScannedAtTheBar`; `infra/scripts/image-scan.sh` `contains: refuses leak-in-layer "github-pat"` `contains: refuses leak-in-env "aws-access-key-id"` `contains: --image-config-scanners misconfig,secret` |
| T6 | The application image's build context is the boot jar alone (`.dockerignore` an allow-list) and its runtime stage sets no environment; CI's image job refuses an image whose `/app` is not exactly the jar's entries or whose environment is not the base's, so no local file, `.env` or configuration beyond the jar's reaches it | EXISTS | `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theBuildContextIsTheBootJarAlone`; `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theRuntimeStageAddsTheJarsFourLayersAndNothingElse`; `infra/scripts/image-build.sh` `contains: /app is not exactly the boot jar's entries` `contains: the image sets environment of its own` |
| T6 | Rotation and revocation of every credential class, rehearsed | PLANNED | `P15-TSK-020` |
| T7 | Locally (compose and the test databases built from the initialisation scripts), every role - the migrator, the application and report generation's - is `NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT`, and no later statement alters or grants that away | EXISTS | `app/src/test/java/com/finapp/app/architecture/LocalDatabaseRolesAreUnprivilegedTest.java#everyRoleIsUnprivileged` |
| T7 | In a deployed database, the one-time bootstrap step creates the three roles with every attribute the local script gives them, `NOBYPASSRLS NOREPLICATION` besides and the same database rights; it sends a SCRAM verifier computed in the process, never the password; and it stops on a role found holding a right it must not | EXISTS | `migrator/src/test/java/com/finapp/migrator/RoleBootstrapTest.java#everyRoleCarriesTheLocalScriptsAttributes`; `migrator/src/test/java/com/finapp/migrator/RoleBootstrapTest.java#anExistingRoleHoldingARightIsRefused`; `migrator/src/test/java/com/finapp/migrator/RoleBootstrapTest.java#thePasswordNeverReachesTheServer`; `infra/scripts/migrator-verify.sh` `contains: the roles' attributes are not LOGIN NOSUPERUSER` |
| T7 | The application role cannot become the migrator, create a role, make itself a superuser, run a program on the host or read password hashes | EXISTS | `platform/src/test/java/com/finapp/platform/audit/ApplicationRoleGrantsTest.java#theRoleCannotEscalate` |
| T7 | The application role holds no DDL and cannot reach the migration history | EXISTS | `platform/src/test/java/com/finapp/platform/audit/ApplicationRoleGrantsTest.java#theRoleIsConfinedToData` |
| T7 | `sslmode=verify-full` required to a database off loopback, weaker modes refused at startup | EXISTS | `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#remoteWithoutTlsIsRefused`; `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#weakerModesAreRefused` |
| T7 | MFA secrets, KYC documents, payment and payout evidence encrypted field by field (AES-256-GCM), each cipher refusing both a tampered ciphertext and the wrong key | EXISTS | `identity/src/test/java/com/finapp/identity/SecretCipherTest.java#tamperingIsRefused`; `identity/src/test/java/com/finapp/identity/SecretCipherTest.java#anotherKeyCannotDecrypt`; `kyc/src/test/java/com/finapp/kyc/DocumentCipherTest.java#tamperIsRefused`; `kyc/src/test/java/com/finapp/kyc/DocumentCipherTest.java#wrongKeyIsRefused`; `payments/src/test/java/com/finapp/payments/EvidenceCipherTest.java#tamperingAndWrongKeyRefuse`; `merchant/src/test/java/com/finapp/merchant/PayoutEvidenceCipherTest.java#tamperAndWrongKeyAreOneFailure` |
| T7 | The database and the brokers have no public address and sit on the workload subnets each root gives them; the database's volume, the brokers' volumes and the SCRAM secrets are encrypted under customer-managed keys each root must supply, an alias or another account's or region's key refused. Asserted over a plan against mock providers, never applied; CI's check refuses a plant of each | EXISTS | `infra/terraform/modules/postgres/tests/postgres.tftest.hcl` `contains: The database has a public address.` `contains: The database's volume is not encrypted under the key this module is given.`; `infra/terraform/modules/kafka/tests/kafka.tftest.hcl` `contains: The brokers' volumes are not encrypted under the key this module is given.` `contains: A SCRAM secret is not under the customer-managed key MSK requires.`; `infra/terraform/environments/production/tests/production.tftest.hcl` `contains: Production's database is not on exactly the workload subnets.` `contains: Production's brokers are not on exactly the workload subnets.` `contains: a_data_tier_key_of_another_account_is_refused` |
| T7 | A backup under a key separate from the volume's: RDS encrypts its automated backups and snapshots with the instance's own key and offers no setting, so the only separation available is a copy crossing an account or region. The module requires that copy's destination, and no such vault exists. Point-in-time recovery stays under the volume's key whatever is chosen | GAP | `owner item 23`; owner: the owner, since 2026-11-09 |
| T7 | No workload subnet is public: none assigns a public address on launch, none has a route to the internet gateway, each leaves through its own zone's NAT gateway; each root gives the cluster and its node groups those subnets alone. Flow logs record every flow in the VPC. Asserted over a plan against a mock provider, never applied; CI's check refuses a plant of each | EXISTS | `infra/terraform/modules/network/tests/network.tftest.hcl` `contains: A workload subnet assigns a public address on launch.` `contains: A workload subnet's default route reaches the internet gateway.` `contains: A workload subnet's default route does not leave through the NAT gateway of its own zone.` `contains: The VPC's flow log does not cover every flow, accepted and rejected.`; `infra/terraform/environments/staging/tests/staging.tftest.hcl` `contains: The staging cluster is not on exactly the workload subnets.`; `infra/terraform/environments/production/tests/production.tftest.hcl` `contains: The production cluster is not on exactly the workload subnets.`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#ciRunsEveryCheckOverTheTreeAndItsPlants` |
| T7 | Field-level keys as key rings, so a leaked version is bounded to its rows | PLANNED | `P15-TSK-019` |
| T7 | Report generation reads as `finapp_reporting`: every migration's grant to it is `SELECT` on one of the report run's 23 tables or `USAGE` on their four schemas, the whole register is granted, and the role is created `NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT` | EXISTS | `app/src/test/java/com/finapp/app/architecture/ReportingRoleIsSelectOnlyTest.java#theRoleReadsTheRegisterAndNothingElse`; `app/src/test/java/com/finapp/app/architecture/LocalDatabaseRolesAreUnprivilegedTest.java#everyRoleIsUnprivileged` |
| T7 | The reporting pool never connects as a role that writes: startup refuses a reporting user naming the application's own role (in any case, whichever property names it), the migrator or the superuser; and it reaches the database with the writer's driver settings, `sslmode` among them | EXISTS | `app/src/test/java/com/finapp/app/database/ReportingPoolRoleTest.java#theWritersRoleIsRefused`; `app/src/test/java/com/finapp/app/database/ReportingPoolRoleTest.java#aWritingRoleIsRefused`; `app/src/test/java/com/finapp/app/database/ReportingPoolTransportTest.java#theWritersSslModeIsCarried` |
| T8 | KYC callbacks: HMAC-SHA256 over the raw request, compared in constant time, the wrong signature refused | EXISTS | `kyc/src/test/java/com/finapp/kyc/CallbackSignatureTest.java#aWrongSignatureIsRefused`; `kyc/src/test/java/com/finapp/kyc/CallbackSignatureTest.java#theComparisonIsConstantTime` |
| T8 | Payment webhooks: a signature that tampering breaks, and a two-sided freshness window | EXISTS | `payments/src/test/java/com/finapp/payments/WebhookSignatureTest.java#roundTripAndTamper`; `payments/src/test/java/com/finapp/payments/WebhookSignatureTest.java#theWindowIsTwoSided` |
| T8 | The instant-rail door: a wrong signature, a missing header and a stale timestamp are one uniform `401`, with nothing written | EXISTS | `app/src/test/java/com/finapp/app/payments/PayByBankDatabaseTest.java#anUnsignedCallbackDiesAtTheDoor` |
| T8 | A delivery repeated three times lands one dedupe record, and every copy is retained as evidence | EXISTS | `app/src/test/java/com/finapp/app/payments/PaymentWebhookDatabaseTest.java#aTripleDeliveryLandsOneDedupeRecordAndThreeEvidenceRows` |
| T8 | FX and corridor callbacks are hints: a signed but forged callback is acknowledged and moves nothing the inquiry does not confirm (both rails), and is retained as evidence (asserted for FX); an unsigned or stale one is `401` and moves nothing (both rails) | EXISTS | `app/src/test/java/com/finapp/app/fx/FxCallbackDuplicateDatabaseTest.java#aForgedSignedCallbackMovesNothing`; `app/src/test/java/com/finapp/app/fx/FxCallbackDuplicateDatabaseTest.java#unsignedOrStaleMovesNothing`; `app/src/test/java/com/finapp/app/fx/OutboundCreditResolutionDatabaseTest.java#callbacksAreHints` |
| T8 | Payment and instant callbacks are hints: a forged-but-signed callback of each kind the door adopted (card authorization, capture, void, refund, clearing and dispute; instant execution and rejection on a waiting pay-in, execution on a concluded one or naming nothing of ours, return and withdrawal) reaches no applier and parks nothing unless the provider's authenticated inquiry supports it; an inquiry with no usable answer adopts nothing and the delivery is still acknowledged; a duplicate delivery makes no second inquiry | EXISTS | `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#aForgedAuthorizationMovesNothing`; `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#aForgedCaptureMovesNothing`; `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#aForgedVoidMovesNothing`; `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#aForgedRefundMovesNothing`; `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#aForgedClearingMovesNothing`; `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#aForgedDisputeMovesNothing`; `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#anIndeterminateInquiryLeavesTheOutcomeToTheSweep`; `app/src/test/java/com/finapp/app/payments/PaymentWebhookHintsTest.java#aDuplicateMakesNoSecondInquiry`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#aForgedExecutionMovesNothing`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#aForgedRejectionMovesNothing`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#aForgedExecutionOnAConcludedAttemptParksNothing`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#aForgedUnattributedExecutionParksNothing`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#aForgedReturnMovesNothing`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#aForgedWithdrawalMovesNothing`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#anIndeterminateInquiryLeavesTheOutcomeToTheSweep`; `app/src/test/java/com/finapp/app/payments/InstantCallbackHintsTest.java#aDuplicateMakesNoSecondInquiry` |
| T8 | Provider callback routes served only by name on the public allow-list | PLANNED | `P15-TSK-011` |
| T8 | Callback secrets verified against the current and the previous secret during a rotation window | PLANNED | `P15-TSK-019` |
| T8 | A callback secret's rotation rehearsed | PLANNED | `P15-TSK-020` |
| T9 | A non-loopback bootstrap over `PLAINTEXT` refuses startup | EXISTS | `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#plaintextOffTheMachineIsRefused` |
| T9 | Lending's origination consumers treat an event as a hint: a forged or late event changes nothing the authoritative read would not | EXISTS | `lending/src/test/java/com/finapp/lending/LoanOriginationDatabaseTest.java#aForgedOrLateEventChangesNothing` |
| T9 | An event payload cannot carry a credential derivation | EXISTS | `app/src/test/java/com/finapp/app/domain/CredentialReachesNoEmittedSinkTest.java#anEventPayloadRefusesADerivation` |
| T9 | Off loopback every protocol but `SASL_SSL` refuses startup, `SASL_PLAINTEXT` and `SSL` included, and a protocol Kafka does not define is refused on loopback too | EXISTS | `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#saslWithoutTlsOffTheMachineIsRefused`; `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#everyProtocolButSaslSslIsRefusedOffTheMachine`; `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#anUnknownProtocolIsRefusedEverywhere` |
| T9 | A SASL client with no credential refuses startup, and so does the marked local SCRAM password off loopback | EXISTS | `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#saslNeedsACredential`; `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#theMarkedLocalPasswordIsConfinedToLoopback` |
| T9 | Every bootstrap entry is read strictly as `host:port` or `[IPv6]:port`, on loopback too, so a `scheme://` prefix the client skips cannot pass a remote broker as a local one; the guard reads the bootstrap list only, not the listeners the brokers advertise after first contact | EXISTS | `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#aBootstrapEntryThatIsNotStrictlyHostAndPortIsRefused`; `app/src/test/java/com/finapp/app/security/KafkaTransportGuardTest.java#aStrictListIsRead` |
| T9 | A broker connection is made only in the composition root, behind the guard: no other production class constructs one, calls `plaintext`, or calls either adapter's `connect` | EXISTS | `app/src/test/java/com/finapp/app/architecture/NoDirectBrokerPublicationRulesTest.java#brokerConnectionsAreMadeBehindTheGuard`; `app/src/test/java/com/finapp/app/architecture/NoDirectBrokerPublicationRulesTest.java#aConnectionOutsideTheGuardedRootIsRejected` |
| T9 | Both clients' connection is rendered in one place: SCRAM-SHA-512 with the password reaching the client's own JAAS reading intact (quotes, backslashes and line breaks included), hostname verification fixed on, a PEM truststore when one is configured | EXISTS | `platform/src/test/java/com/finapp/platform/outbox/BrokerConnectionTest.java#saslSslRendersScramAndTls`; `platform/src/test/java/com/finapp/platform/outbox/BrokerConnectionTest.java#theJaasLineIsEscaped`; `platform/src/test/java/com/finapp/platform/outbox/KafkaEventPublisherTest.java#theProducerConnectsAsTheConsumerDoes`; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverConfigurationTest.java#theConnectionIsTheShared` |
| T9 | No committed file holds a SCRAM password, the one inside a JAAS line included, quoted or bare | EXISTS | `app/src/test/java/com/finapp/app/security/CommittedConfigurationHoldsNoSecretTest.java#credentialShapesAreRejected`; `app/src/test/java/com/finapp/app/security/CommittedConfigurationHoldsNoSecretTest.java#noCommittedConfigurationHoldsACredentialLiteral` |
| T9 | The ACLs written for the managed broker: the application's principal may describe, read and write the catalogued topics and read its consumer groups, and holds no cluster operation; no principal may delete a topic as declared (the administrator's cluster `Alter` edits ACLs, so it could grant itself more at run time - who holds that credential is the operating answer) | EXISTS | `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#theApplicationHoldsLeastPrivilege`; `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#noPrincipalMayDelete` |
| T9 | The catalogue's ACLs and one SCRAM user per principal are planned from `infra/kafka/topics.yaml` itself: the application's principal describe, read and write per catalogued topic by literal name and read on the groups prefixed `finapp.`, no permission on the cluster; the provisioner's create and configure and cluster alter and describe, nothing delete; TLS and SCRAM the only way in, nothing unauthenticated; the SCRAM secrets named and keyed as MSK requires, with no value in state; ACLs refused by default once the cluster reaches its last stage. Asserted over a plan against mock providers, never applied: no broker has ever enforced one of these | EXISTS | `infra/terraform/modules/kafka/tests/kafka.tftest.hcl` `contains: The application holds a topic permission by pattern or wildcard, not by literal name.` `contains: A principal may delete, or holds a blanket operation, or the ACL is not a plain grant.` `contains: A client can reach the brokers unauthenticated.` `contains: A client or a broker can talk to a broker without TLS.` `contains: A SCRAM secret's name does not start with AmazonMSK_, which MSK requires.` `contains: A principal reaches any resource no ACL names.` `contains: The module does not plan the catalogue it is given: it holds a copy.`; `infra/terraform/environments/production/tests/production.tftest.hcl` `contains: Production's topics are not the catalogue's`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#theCatalogueHasNoCopyUnderTerraform` |
| T9 | The ACLs and SCRAM users applied to a real managed broker: it needs an account, values in the secrets, and a runner inside the network holding the provisioner's credential, in three applies, the middle one with ACLs not yet enforced. None exists | GAP | `owner item 23`; owner: the owner, since 2026-11-09 |
| T9 | A TLS and SCRAM handshake against a real broker, with the local CA | PLANNED | `P15-TSK-012` |
| T10 | The application role cannot `UPDATE` any column of, `DELETE` from or `TRUNCATE` the audit record | EXISTS | `platform/src/test/java/com/finapp/platform/audit/AuditImmutabilityTest.java#updateIsDeniedOnEveryColumn`; `platform/src/test/java/com/finapp/platform/audit/AuditImmutabilityTest.java#deleteIsDenied`; `platform/src/test/java/com/finapp/platform/audit/AuditImmutabilityTest.java#truncateIsDenied` |
| T10 | Every catalogued auditable action is referenced by production code, or declared not yet emitted with its owner | EXISTS | `app/src/test/java/com/finapp/app/audit/AuditCompletenessTest.java#everyActionIsEmittedOrDeclaredNotToBe` |
| T10 | An operator act's records carry the operator population, and a refused step-up writes its record in its own committed transaction, once per refused request | EXISTS | `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#anOperatorActIsTypedEmployee`; `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#aRefusedStepUpIsRecorded`; `app/src/test/java/com/finapp/app/session/StepUpRefusalsTest.java#anOperationsRefusalIsRecordedOnce` |
| T10 | The retention policy names every data class, leaves each period a jurisdiction would set open, says no audit record is ever deleted, and designs archival as a verified copy to write-once storage. The guard holds what the document states; the row above holds that the role cannot delete | EXISTS | `app/src/test/java/com/finapp/app/architecture/RetentionPolicyIsCompleteTest.java#everyClassHasARow`; `app/src/test/java/com/finapp/app/architecture/RetentionPolicyIsCompleteTest.java#everyJurisdictionDependentPeriodIsOpen`; `app/src/test/java/com/finapp/app/architecture/RetentionPolicyIsCompleteTest.java#auditIsNeverDeletedAndItsArchivalIsACopy` |
| T10 | The audit archival itself: the export, its manifest and digest chain, and its verification. Designed, not built, so no archive copy exists yet | GAP | `owner item 18`; owner: the owner, since 2026-11-08 |
| T10 | Write-once storage for the audit archive: Object Lock on from the bucket's creation with a default rule, its mode and period required inputs with no default; versions kept; KMS-encrypted under a key each root refuses being the database's; public access off; a request without TLS refused; every principal refused deletion and any bypass of the lock; nothing expiring. Asserted over a plan against a mock provider, never applied; the archival that would write here is the `GAP` row above, so no object is locked today | EXISTS | `infra/terraform/modules/object-storage/tests/object-storage.tftest.hcl` `contains: Object Lock is not on at the audit archive's creation` `contains: The audit archive's policy does not refuse deletion by every principal.` `contains: A lifecycle rule expires an archived object.` `contains: A bucket admits public access`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#anOpenDecisionHasNoDefault`; `infra/terraform/environments/production/tests/production.tftest.hcl` `contains: the_databases_own_key_is_refused_for_the_archive` `contains: Production's audit archive is not locked with the mode and period the root was given.` |
| T10 | The audit archive's lock period and mode: the period is `OPEN` until a real jurisdiction is chosen (`DATA_RETENTION.md` §3), so neither root can be planned for real until someone chooses both | GAP | `owner item 23`; owner: the owner, since 2026-11-09 |
| T11 | Startup guards refuse an unsafe configuration on every replica: a fleet whose pools exceed the database's connections (the guard wired, so a violation stops the instance), any configured `sslmode` source weaker than `verify-full`, a settlement pull source in cleartext | EXISTS | `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingGuardTest.java#hikarisDefaultExhaustsPostgresDefault`; `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingIsConfiguredTest.java#theGuardIsWired`; `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#everyConfiguredSourceMustVerify`; `app/src/test/java/com/finapp/app/security/ProviderTransportGuardTest.java#startupConsultsEverySourceUrl` |
| T11 | Infrastructure code pinned: every environment root requires Terraform at exactly the version of the image that checks it and every provider at one exact version, the same in both roots, and each root's lock file holds exactly those providers at those versions. It is checked on every pull request: CI's `iac` job, ungated, runs a script that runs `fmt`, `tflint`, `trivy config`, `validate` and `terraform test` over the whole tree, then runs each check again over a planted defect it must refuse | EXISTS | `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#everyRootPinsTerraformAndEveryProviderExactly`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#everyLockFileHoldsThePinnedVersions`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#ciRunsEveryCheckOverTheTreeAndItsPlants` |
| T11 | Infrastructure applied only from a reviewed plan, and a scheduled plan reporting drift. Both need a real account, which Phase 15 does not have (ADR-0121 decision 6), so nothing yet holds either | GAP | `owner item 7`; owner: the owner, since 2026-11-08 |
| T11 | GitOps self-heal, and two persons on every production change | PLANNED | `P15-TSK-017` |
| T11 | The configuration register, and an alert when replicas disagree for longer than a rollout | PLANNED | `P15-TSK-031` |
| T12 | The build's Flyway tasks are forward-only in every module with migrations (21 today): `clean` disabled, validated before migrating, never out of order. This holds the Gradle and CI path, not the deployed runner | EXISTS | `app/src/test/java/com/finapp/app/architecture/MigrationSettingsAreForwardOnlyTest.java#everyMigratingModuleIsForwardOnly` |
| T12 | Readiness reports down without the database, while liveness ignores it | EXISTS | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#readinessReflectsTheDatabase`; `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#livenessIgnoresTheDatabase` |
| T12 | The migrator image applies every module's migrations in the build's order with each schema's build settings and the build's Flyway version; the wait for a lock is bounded below the application pool's so a migration never queues in front of the writers; and its result is compared with the build's on a real database: schema history and schema identical, a second run a no-op, concurrent runs each migration once | EXISTS | `app/src/test/java/com/finapp/app/architecture/MigratorOrderIsTheBuildsTest.java#theOrderIsTheBuilds`; `migrator/src/test/java/com/finapp/migrator/MigratorMatchesTheBuildTest.java#everySchemaHasTheBuildsSettings`; `migrator/src/test/java/com/finapp/migrator/MigratorMatchesTheBuildTest.java#theRunnerUsesTheBuildsFlyway`; `migrator/src/test/java/com/finapp/migrator/MigratorMatchesTheBuildTest.java#theLockBoundEndsBeforeThePoolGivesUp`; `migrator/src/test/java/com/finapp/migrator/MigrationRunTest.java#aLockTimeoutGivesUp`; `migrator/src/test/java/com/finapp/migrator/MigratorImageTest.java#ciBuildsAndVerifiesIt`; `infra/scripts/migrator-verify.sh` `contains: the image's schema history is not the build's` `contains: a migration was applied twice, or not at all` `contains: a migration behind a held lock` |
| T12 | The migration Job's manifest under the written contract: run before the rollout, its `backoffLimit`, `podFailurePolicy` and deadline | PLANNED | `P15-TSK-012` |
| T12 | A migration added since the last release cannot drop, rename, change a type, truncate, or tighten (a new `CHECK`, trigger, `UNIQUE` or foreign key, `SET NOT NULL`, `ADD COLUMN … NOT NULL` with no default, a `REVOKE`, a replaced function body) unless its file carries a well-formed contract marker naming a release already out; a tightening on a table created in the same file needs none. Each class is refused on a planted file through the one path a real file takes, and the build runs that path over the real tree, read whole with no floor to prove it parses them | EXISTS | `app/src/test/java/com/finapp/app/architecture/ExpandOnlyLintTest.java#everyRefusedClassIsRefused`; `app/src/test/java/com/finapp/app/architecture/ExpandOnlyLintTest.java#aMarkedContractIsAccepted`; `app/src/test/java/com/finapp/app/architecture/ExpandOnlyLintTest.java#aMarkerAheadOfTheLastReleaseIsRefused`; `app/src/test/java/com/finapp/app/architecture/ExpandOnlyLintTest.java#tighteningOnANewTableNeedsNoMarker`; `app/src/test/java/com/finapp/app/architecture/MigrationsAreExpandOnlyTest.java#everyMigrationSinceTheLastReleaseIsExpandOnly`; `app/src/test/java/com/finapp/app/architecture/MigrationsAreExpandOnlyTest.java#theLintReadsEveryRealMigration` |
| T12 | A replica on a schema older than its build expects answers readiness `503 OUT_OF_SERVICE` and liveness `200`; on the expected schema or a newer one, as in a rollout or a rollback, it is ready. Versions compare as versions, and a read that was refused, or never succeeded, is not ready | EXISTS | `app/src/test/java/com/finapp/app/database/SchemaVersionReadinessTest.java#falseOnAnOlderSchema`; `app/src/test/java/com/finapp/app/database/SchemaVersionReadinessTest.java#trueOnTheExpectedSchema`; `app/src/test/java/com/finapp/app/database/SchemaVersionReadinessTest.java#trueOnANewerSchema`; `app/src/test/java/com/finapp/app/database/SchemaVersionReadinessTest.java#readinessAndNotLiveness`; `app/src/test/java/com/finapp/app/database/SchemaVersionGuardTest.java#comparedAsVersions`; `app/src/test/java/com/finapp/app/database/SchemaVersionGuardTest.java#aRefusedReadIsNotReady`; `app/src/test/java/com/finapp/app/database/SchemaVersionGuardTest.java#beforeTheFirstReadNothingIsProven` |
| T12 | The versions a replica requires are the build's: embedded from the migrations on its runtime classpath, held equal to a scan of that classpath and a walk of the repository | EXISTS | `app/src/test/java/com/finapp/app/database/ExpectedSchemaVersionsTest.java#theEmbeddedFileIsTheClasspath`; `app/src/test/java/com/finapp/app/database/ExpectedSchemaVersionsTest.java#theEmbeddedFileIsTheRepository` |
| T12 | The readiness probe never waits on this read, so a database that is gone costs it nothing beyond what `db` already spends: one read at a time on its own thread, one statement per floor | EXISTS | `app/src/test/java/com/finapp/app/database/SchemaVersionGuardTest.java#theProbeNeverReads`; `app/src/test/java/com/finapp/app/database/SchemaVersionGuardTest.java#oneReadAtATime`; `app/src/test/java/com/finapp/app/database/SchemaVersionGuardTest.java#tenReplicas` |
| T12 | The guard's read through the application's own role on PostgreSQL: every schema at its expected version, the history tables still closed to that role, a failed row not read (`SchemaVersionReadDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T12 | A rollout loses no capacity and stacks no terminating pods: `maxUnavailable: 0`, one surge, and `minReadySeconds` at least a grace that covers every step of a stop, the steps' bounds read from the code and production's file. CI's manifest check holds the same on every rendered overlay, each refusal proven by a planted violation in the same run | EXISTS | `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#theRolloutIsBoundedByTheGrace`; `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#theShutdownBudgetIsTheCodes`; `infra/scripts/manifest-check.sh` `contains: refused rollout-unavailable rollout-strategy` `contains: refused rollout-min-ready rollout-min-ready` `contains: refused rollout-grace-short rollout-grace` |
| T12 | Release N − 1 run on N's schema, the rolling update and the rollback rehearsed with the financial census | PLANNED | `P15-TSK-012` |
| T12 | The promotion refusing a rollback across a contract marker | PLANNED | `P15-TSK-017` |
| T12 | Every event type, version and field is read from the compiled code and compared with the committed catalogue of the last release: a removed field, a changed kind or a field no longer always written fails the build, and a write the reader cannot follow fails it too | EXISTS | `app/src/test/java/com/finapp/app/architecture/EventCatalogueTest.java#onlyAdditiveChangesSinceTheLastRelease`; `app/src/test/java/com/finapp/app/architecture/EventCatalogueTest.java#everyWriteIsReadable`; `app/src/test/java/com/finapp/app/architecture/EventCompatibilityTest.java#aRemovedFieldIsRefused`; `app/src/test/java/com/finapp/app/architecture/EventCompatibilityTest.java#aTypeChangeIsRefused`; `app/src/test/java/com/finapp/app/architecture/EventCompatibilityTest.java#requiredMadeOptionalIsRefused`; `app/src/test/java/com/finapp/app/architecture/EventFlowsTest.java#theUnreadableIsReported` |
| T12 | The handler set is read from the composed beans and diffed against the last release: a handler added or removed with no recorded way through fails the build, and a consumed type cannot take a new version before its consumers have learned it. The build proves the release recorded the step, not that it was run | EXISTS | `app/src/test/java/com/finapp/app/architecture/HandlerSetCatalogueTest.java#everyChangeToTheHandlerSetIsRecorded`; `app/src/test/java/com/finapp/app/architecture/HandlerSetCatalogueTest.java#noConsumedTypeOutrunsItsConsumers`; `app/src/test/java/com/finapp/app/architecture/HandlerSetDiffTest.java#aPlantedHandlerIsRefused`; `app/src/test/java/com/finapp/app/architecture/HandlerSetDiffTest.java#aRemovedHandlerIsDiffedToo`; `app/src/test/java/com/finapp/app/architecture/HandlerSetDiffTest.java#aConsumedTypeWaitsForItsConsumers` |
| T12 | An audited order to replay one event type over a time window of first publication: the selected events are requeued for the relay and the order recorded | EXISTS | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aReplayRequeuesAndExtendsTheDedupe` |
| T12 | A handler added to an existing group served by such a replay over its rollout and rollback windows, none lost and none applied twice, on a cluster | PLANNED | `P15-TSK-012` |
| T13 | Session liveness judged on the database's clock, never against a caller's instant | EXISTS | `app/src/test/java/com/finapp/app/architecture/SessionTimeIsTheDatabasesTest.java#noBoundIsComparedWithACallersInstant` |
| T13 | The five outbound stores stamp every send permit with the database's `statement_timestamp()` and bind no caller's instant | EXISTS | `app/src/test/java/com/finapp/app/architecture/SendPermitsAreTheDatabasesTest.java#everyPermitAssignmentIsTheDatabases` |
| T13 | No production class reads ambient time (the build rule) | EXISTS | `app/src/test/java/com/finapp/app/architecture/NoAmbientTimeRulesTest.java#noAmbientTimeIsRead` |
| T13 | PI-013: a month's close run is named by its lock's identity, so the view reads the present lock's run even over a store whose latest is an earlier lock's run (what a stepped clock made of `started_at`); the latest-run and latest-proposal statements rank by the rows - present lock, `PROPOSED`, revision - before any stamp, pinned as text | EXISTS | `accounting/src/test/java/com/finapp/accounting/CloseRunsTest.java#theRunIsTheLocksWhateverAStampSays`; `accounting/src/test/java/com/finapp/accounting/CloseRunStatementsTest.java#theLatestRunIsRankedByTheRows`; `accounting/src/test/java/com/finapp/accounting/PeriodCloseStatementsTest.java#theLatestCloseIsRankedByTheRows` |
| T13 | PI-014 item 1: lending `V033` and bnpl `V021` end a retired terms version after its latest offer, start its successor exactly there by version number, and floor an offering instant at its version's start - pinned as text | EXISTS | `lending/src/test/java/com/finapp/lending/LoanTermsClockMigrationTest.java#theRetirementEndsAfterTheLatestOffer`; `lending/src/test/java/com/finapp/lending/LoanTermsClockMigrationTest.java#theSuccessorStartsExactlyThere`; `lending/src/test/java/com/finapp/lending/LoanTermsClockMigrationTest.java#theOfferIsNeverBeforeItsVersion`; `bnpl/src/test/java/com/finapp/bnpl/BnplTermsClockMigrationTest.java#theRetirementEndsAfterTheLatestOffer`; `bnpl/src/test/java/com/finapp/bnpl/BnplTermsClockMigrationTest.java#theSuccessorStartsExactlyThere`; `bnpl/src/test/java/com/finapp/bnpl/BnplTermsClockMigrationTest.java#theOfferIsNeverBeforeItsVersion` |
| T13 | PI-013's and PI-014's clock-step regression cases run green on PostgreSQL with the database's clock stepped back on demand (`CloseRunClockStepDatabaseTest`, `LoanTermsDatabaseTest`, `BnplTermsDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T13 | An instance whose clock has moved from the database's is visible per instance, its reading NaN and never zero when unreadable: ADR-0063's clamp hides the skew from the writes, and `InstanceClockOffset` tickets on it | EXISTS | `app/src/test/java/com/finapp/app/telemetry/ClockOffsetMetricsTest.java#theOffsetIsSigned`; `app/src/test/java/com/finapp/app/telemetry/ClockOffsetMetricsTest.java#unreadableIsNaN` |
| T13 | The lock's grace, the month the open leg opens and the month a prior-period correction lands in are the database's date, read holding the row where a row is held, with this instance's clock and the database's disagreeing both ways | EXISTS | `accounting/src/test/java/com/finapp/accounting/AccountingPeriodsTest.java#theGraceIsTheDatabases`; `accounting/src/test/java/com/finapp/accounting/AccountingPeriodsTest.java#theDateIsReadHoldingTheRow`; `accounting/src/test/java/com/finapp/accounting/AccountingPeriodsTest.java#theOpenLegIsTheDatabases`; `accounting/src/test/java/com/finapp/accounting/PriorPeriodAdjustmentsTest.java#theMonthIsTheDatabases`; `accounting/src/test/java/com/finapp/accounting/PriorPeriodAdjustmentsTest.java#theDateIsReadHoldingTheCorrectedRow` |
| T13 | The submissions' keyset and the newest answers are ordered by an insertion sequence, pinned as text; accounting `V014` adds it expand-only, a column no application statement names and a plain named value is refused for, backfilled in the order the release before it serves, each pinned as text | EXISTS | `accounting/src/test/java/com/finapp/accounting/RegulatorySubmissionStatementsTest.java#theOrderingsAreTheInsertionSequence`; `accounting/src/test/java/com/finapp/accounting/RegulatoryOrderingMigrationTest.java#theBackfillIsTheOrderTheLastReleaseServes`; `accounting/src/test/java/com/finapp/accounting/RegulatoryOrderingMigrationTest.java#theColumnIsAnIdentityAPlainValueCannotName`; `accounting/src/test/java/com/finapp/accounting/RegulatoryOrderingMigrationTest.java#expandOnly` |
| T13 | The period dates' and the submissions' orderings' clock-step cases run green on PostgreSQL with the database's clock moved on demand (`PeriodDatesAreTheDatabasesDatabaseTest`, four cases of `RegulatorySubmissionDatabaseTest`, one of `PriorPeriodAdjustmentDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T13 | Recovery tokens, contact-channel verification, checkout expiry, the card fee version in force and payout-destination cooling-off are judged and stamped on the database's clock: no bound of the five stores is compared with a bind parameter, each deciding statement reads the database's clock in the statement, and the services above them carry that reading, the sweepers holding no clock | EXISTS | `app/src/test/java/com/finapp/app/architecture/StoredBoundsAreTheDatabasesTest.java#noBoundIsComparedWithACallersInstant`; `app/src/test/java/com/finapp/app/architecture/StoredBoundsAreTheDatabasesTest.java#eachStatementReadsTheDatabasesClock`; `app/src/test/java/com/finapp/app/architecture/StoredBoundsAreTheDatabasesTest.java#theAnswerAgrees`; `app/src/test/java/com/finapp/app/architecture/StoredBoundsAreTheDatabasesTest.java#theSweepersHoldNoClock` |
| T13 | A new comparison of a timestamp column with a bind parameter, in any production source, fails the build until somebody has classified whose instant it is. Read from the migrations and the string literals: a `?` cannot say who read it, so the census is a person's reading per class and column | EXISTS | `app/src/test/java/com/finapp/app/architecture/StoredBoundsAreTheDatabasesTest.java#everyStoreIsInTheCensus`; `app/src/test/java/com/finapp/app/architecture/StoredBoundsAreTheDatabasesTest.java#theRuleSeesAPlantedComparison` |
| T13 | The card fee version in force is resolved after the merchant's share lock, in a statement that takes no instant, and the card fee pin is stamped by its own insert on the database's clock with its version in force by that stamp | EXISTS | `app/src/test/java/com/finapp/app/checkout/CheckoutSessionsFeeVersionOrderTest.java#theVersionIsResolvedAfterTheShareLock`; `app/src/test/java/com/finapp/app/architecture/StoredBoundsAreTheDatabasesTest.java#thePinIsBoundToTheDatabasesClock` |
| T13 | The five stores' bounds run green on PostgreSQL with the instance's clock and the database's disagreeing, each case asserting that first (`StoredBoundsClockSkewDatabaseTest`, the rewritten expiry and cooling-off suites, written and owed) | PLANNED | `P15-TST-002` |
| T13 | Instance clocks outside the five. In the SQL the census names: routing's version in force, which does judge a stored `effective_from` on the caller's clock (it chooses a rail, not a price), a sweep's `UNKNOWN` arm, settlement's backoff, a gauge's horizon and risk's look-back windows. In Java, which the census cannot see: the dispute door, judging a stored `respond_by` on the instance's clock | GAP | `owner item 27`; owner: the owner, since 2026-11-09 |
| T13 | Lending's functions, the helpers its triggers call for the business date included, read the database's clock whatever a role's `search_path` holds: every clock call names `pg_catalog` | EXISTS | `app/src/test/java/com/finapp/app/architecture/OwnerReachOverHistoryIsClosedTest.java#noFunctionReadsTheClockThroughTheSearchPath`; `app/src/test/java/com/finapp/app/architecture/OwnerReachOverHistoryIsClosedTest.java#theRuleReadsCodeNotText`; `infra/scripts/migrator-verify.sh` `contains: a shadowing schema first in the path` |
| T13 | 108 functions in eleven other schemas (accounting, bnpl, checkout, credit, crossborder, fx, ledger, merchant, payments, risk, settlement) still read a clock through a mutable `search_path`, each named by the same guard | PLANNED | `P15-TSK-040` |
| T13 | Every single-row read or value picked by a stamp outside the close and the terms, in the shapes the guard reads from the code (Java statements and text blocks, constants inlined; every function's and view's latest definition), is named in its census with a recorded judgement and reason, held equal to the code: a new read in any such shape, or a named read gone, is red, and each shape is proved by a plant | EXISTS | `app/src/test/java/com/finapp/app/architecture/StampOrderedReadsAreJudgedTest.java#everyStampReadIsJudged`; `app/src/test/java/com/finapp/app/architecture/StampOrderedReadsAreJudgedTest.java#plantedShapesAreRecognised` |
| T13 | The fixed reads and writes pinned as text: kyc's checks and latest case and payments' attempt ranked by a sequence, a case's predecessor by the chain's tail, a repudiation's continued break (a trace link) preferring it, the chosen routing decision first, the refund by its dispatch key, routing and fee versions floored by version number and the stored start returned | EXISTS | `kyc/src/test/java/com/finapp/kyc/KycStampOrderStatementsTest.java#theLatestDecidedScreeningIsRankedByItsOrder`; `kyc/src/test/java/com/finapp/kyc/KycStampOrderStatementsTest.java#theNewestCheckIsRankedByItsOrder`; `kyc/src/test/java/com/finapp/kyc/KycStampOrderStatementsTest.java#theLatestCaseIsRankedByItsOrder`; `payments/src/test/java/com/finapp/payments/PaymentsStampOrderStatementsTest.java#theAttemptIsRankedByItsOrder`; `payments/src/test/java/com/finapp/payments/PaymentsStampOrderStatementsTest.java#theChosenDecisionComesFirst`; `payments/src/test/java/com/finapp/payments/PaymentsStampOrderStatementsTest.java#theRefundIsNamedByItsKey`; `payments/src/test/java/com/finapp/payments/PaymentsStampOrderStatementsTest.java#anImmediateVersionsStartIsFlooredByVersionNumber`; `risk/src/test/java/com/finapp/risk/RiskStampOrderStatementsTest.java#aCasesPredecessorIsTheTail`; `reconciliation/src/test/java/com/finapp/reconciliation/RepudiationStatementsTest.java#theLastResolvedBreakIsTheTail`; `merchant/src/test/java/com/finapp/merchant/FeeScheduleVersionFloorStatementsTest.java#theStartIsFlooredByVersionNumber` |
| T13 | Risk `V020`: a retired policy version ends after its latest assessment, its successor starts exactly there by version number, and an assessment is never dated before its version began, pinned as text beside a line-by-line diff against the function's `V002` predecessor | EXISTS | `risk/src/test/java/com/finapp/risk/RiskPolicyClockMigrationTest.java#theRetirementEndsAfterTheLatestAssessment`; `risk/src/test/java/com/finapp/risk/RiskPolicyClockMigrationTest.java#theSuccessorStartsExactlyThere`; `risk/src/test/java/com/finapp/risk/RiskPolicyClockMigrationTest.java#theAssessmentIsNeverBeforeItsVersion`; `risk/src/test/java/com/finapp/risk/RiskPolicyClockMigrationTest.java#itReplacesOnlyWhatItNames` |
| T13 | The census's clock-step cases run green on PostgreSQL with a stamp written the wrong way round: risk's retirement, predecessor and signal tie-break, kyc's checks and case, payments' attempt and routing, the fee version floor and the repudiation's continued break (`RiskStampOrderDatabaseTest`, `StampRanksDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T14 | The connection budget is a startup guard against the **declared** instance count (`finapp.database.instances`): a fleet whose pools exceed the database's available connections refuses to start, naming the fix; a declared fleet scaled out past the limit no longer fits; reserved connections count; the guard is wired. A replica count that outgrows its declaration (an HPA) is not caught here | EXISTS | `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingGuardTest.java#hikarisDefaultExhaustsPostgresDefault`; `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingGuardTest.java#scalingOutIsCaught`; `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingGuardTest.java#reservedConnectionsAreLoadBearing`; `app/src/test/java/com/finapp/app/database/ConnectionPoolSizingIsConfiguredTest.java#theGuardIsWired` |
| T14 | Requests and limits on every container, memory equal; and the fleet bounded before anything is deployed: `maxReplicas + 2 × maxSurge + 1` within an overlay's declared instances, those instances' two pools (production's file read for their size) within its database connections less the reserved, and scale-down one pod per period no shorter than the grace. CI's manifest check holds each on every rendered overlay, each refusal proven by a planted violation in the same run. A declaration, as the guard's is: nothing reads a live server | EXISTS | `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#requestsAndLimitsAreSet`; `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#everyOverlaysFleetFitsTheBudget`; `app/src/test/java/com/finapp/app/architecture/KubernetesManifestsTest.java#scaleDownCannotStackTerminatingPods`; `infra/scripts/manifest-check.sh` `contains: refused budget-max-replicas budget-instances` `contains: refused budget-surge budget-instances` `contains: refused budget-fleet budget-fleet` `contains: refused hpa-scale-down-period hpa-scale-down` `contains: refused memory-overcommit resources-memory` `contains: refused null-resources resources-present` `contains: refused budget-one-source-canonical budget-one-source` |
| T14 | `ResourceQuota` and `LimitRange` | PLANNED | `P15-TSK-010` |
| T14 | The budget's peak measured during a rollout and a pod kill | PLANNED | `P15-TSK-012` |
| T14 | Every gauge read off the scrape thread: one scrape of every meter of the real context, each meter's measurements read again on the same thread, asks the database for nothing there (a planted gauge reading it on the scrape is caught), and the reads run on the instance's one gauge thread, where ten concurrent looks at a blocked read start one read. A scrape, still unauthenticated, therefore holds no connection of its own, and a slow database never loses the whole scrape | EXISTS | `app/src/test/java/com/finapp/app/telemetry/NoGaugeReadsOnTheScrapeTest.java#noGaugeReadsTheDatabaseOnTheScrapeThread`; `app/src/test/java/com/finapp/app/telemetry/NoGaugeReadsOnTheScrapeTest.java#aPlantedReadOnTheScrapeIsCaught`; `platform/src/test/java/com/finapp/platform/metrics/GaugeRefresherTest.java#aBlockedReadNeverBlocksALookAndFliesOnce` |
| T15 | An unbalanced journal entry is refused at commit, whoever writes it, raw SQL included (`INV-LED-01`) | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#aDirectUnbalancedInsertIsRefusedAtCommit` |
| T15 | The application role holds no `UPDATE`, `DELETE` or `TRUNCATE` on the journal | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#theColumnSweep` |
| T15 | The journal's append-only trigger binds even the migrator (`INV-HIST-01`) | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#theAppendOnlyTriggerBindsTheMigrator` |
| T15 | In code, the cash book has one poster: `CASH_AT_BANK` is named only by its poster and its reader | EXISTS | `app/src/test/java/com/finapp/app/architecture/CashAtBankHasOnePosterTest.java#cashAtBankIsNamedOnlyByItsPosterAndItsReader` |
| T15 | The Phase 14 schema closed to raw writers: a rebuild born undecided, a journal line joining only an entry of its own transaction, the GL mapping's version and activation locks | REMEDIATION | `P14-REM-004` |
| T15 | Write-offs and the BNPL fee pin enforced for every writer | REMEDIATION | `P14-REM-006` |
| T15 | The application role's `UPDATE (status)` on settlement sources revoked, or routed through a reviewed door | REMEDIATION | `P14-REM-007` |
| T15 | The application's database credential delivered only to the application's pods | PLANNED | `P15-TSK-018` |
| T15 | The database reachable only from the application's and the migrator's pods | PLANNED | `P15-TSK-010` |
| T15 | The database has no public address, sits on the workload subnets alone, and admits one security group on its port, the cluster's nodes, so the credential is no use from outside the cluster's network. Asserted over a plan against a mock provider, never applied. Every pod on a node shares that group, so it narrows who can reach the port and does nothing against a holder of the credential on a node | EXISTS | `infra/terraform/modules/postgres/tests/postgres.tftest.hcl` `contains: The database has a public address.` `contains: Something other than the given security group is admitted to the database.`; `infra/terraform/environments/production/tests/production.tftest.hcl` `contains: Production's database does not admit the cluster's nodes, and only them.` `contains: Production's database is not on exactly the workload subnets.` |
| T15 | As written in the rule files, the product proofs and projection drift page on their first breach, with no budget and no `for`, routed to a placeholder receiver until an on-call tool exists (the test reads each rule's text; only projection drift and the other new correctness rules have `promtool` cases): detection for the books those proofs cover (§5), and nothing for a balanced shift between accounts no proof covers | EXISTS | `app/src/test/java/com/finapp/app/telemetry/AlertCatalogueTest.java#theCorrectnessSignalsPageOnTheirFirstSample` |
| T15 | The source-join census: every journal entry joined by its `reference` and `idempotency_scope` to the domain record that caused it, with equal amounts, on every book (the ten accounts no proof reads among them), paging on its first break; it detects an entry with no domain record behind it, not one whose domain record was written first | PLANNED | `P15-TSK-035` |
| T15 | Refusal of a balanced, rule-shaped entry inserted by a holder of the application's credential (a definer-rights posting path as the journal's only `INSERT`, the projection's `UPDATE` revoked, a role per module), and detection of one whose domain record was written first | GAP | `PI-016`; owner: the owner, since 2026-11-08 |
| T16 | Ten instances submitting one key produce exactly one transfer (`INV-IDEM-01`) | EXISTS | `app/src/test/java/com/finapp/app/transfers/TransferExecutionDatabaseTest.java#tenConcurrentIdenticalKeysProduceOneTransfer` |
| T16 | A key reused with a different request is refused as a distinct conflict, never replayed (`INV-IDEM-03`) | EXISTS | `platform/src/test/java/com/finapp/platform/idempotency/IdempotentExecutorTest.java#differingFingerprintIsRejected` |
| T16 | A merchant's payout key is that merchant's own: two merchants, one key value, two payouts | EXISTS | `app/src/test/java/com/finapp/app/merchant/MerchantPayoutEndpointDatabaseTest.java#aClientKeyIsEachMerchantsOwn` |
| T16 | Every idempotency claim scoped to its principal, each money-moving scope moved behind a tested transition path | REMEDIATION | `X-TSK-003` |
| T16 | No production statement deletes an idempotency record: posting once-ness's second rank (debt row #61) was ruled not settled on 2026-11-08, so no compiled class holds a delete beside the table's name and no migration deletes from it, the retention sweeps' among them, until a uniqueness on the posting scope exists. The application role keeps the `DELETE` an earlier migration granted, and nothing uses it | EXISTS | `app/src/test/java/com/finapp/app/architecture/NoIdempotencyRecordIsSweptTest.java#noProductionClassDeletesAnIdempotencyRecord`; `app/src/test/java/com/finapp/app/architecture/NoIdempotencyRecordIsSweptTest.java#noMigrationDeletesAnIdempotencyRecord` |
| T17 | A redelivered message produces no second effect, and its dedupe record commits with its effect (`INV-IDEM-04`) | EXISTS | `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#redeliveryProducesNoSecondEffect`; `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#theRecordAndTheEffectCommitTogether` |
| T17 | Reordering and duplication together leave the right answer under an ordering key (`INV-EVT-04`) | EXISTS | `platform/src/test/java/com/finapp/platform/inbox/InboxDeliveryOrderTest.java#reorderingAndDuplicationTogether` |
| T17 | Every catalogued topic's retention, plus one segment and the broker's retention check, is below the inbox's dedupe window read from the code, and every topic deletes rather than compacts, on the broker's clock | EXISTS | `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#everyRetentionIsBelowTheDedupeWindow`; `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#retentionIsTimeBasedOnTheBrokersClock` |
| T17 | The consumers are configured to rebalance cooperatively: the cooperative assignor alone under the classic protocol pinned, a 30-second session, 10 records in a 120-second poll budget, no static membership - the client configuration as rendered, not its behaviour on a broker | EXISTS | `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverConfigurationTest.java#theAssignorIsCooperative`; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverConfigurationTest.java#theTimeoutsAreBounded`; `app/src/test/java/com/finapp/app/eventing/InboxPollBudgetTest.java#theBatchFitsThePollBudget` |
| T17 | On a real broker a rolling step moves only the partitions that change hands, the staying member keeps consuming, and what moved redelivers into the dedupe with no second effect (`KafkaRebalanceKafkaTest`, written and owed) | PLANNED | `P15-TST-002` |
| T17 | A handler added to an existing consumer group, whose records an N − 1 replica would acknowledge unseen, fails the build unless the release records a replay of its type over the rollout and rollback windows | EXISTS | `app/src/test/java/com/finapp/app/architecture/HandlerSetDiffTest.java#aPlantedHandlerIsRefused`; `app/src/test/java/com/finapp/app/architecture/HandlerSetDiffTest.java#aRecordedReplayIsAccepted`; `app/src/test/java/com/finapp/app/architecture/HandlerSetCatalogueTest.java#everyChangeToTheHandlerSetIsRecorded` |
| T17 | A replay of an event at or past the inbox's dedupe window is refused whole, nothing requeued, unless every consumer of the type carries the handler's naturally-idempotent mark; the window is exactly the retention given | EXISTS | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aReplayPastTheWindowIsRefused`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#theWindowIsTheRetention`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#naturallyIdempotentTypesMayBeReplayedPastTheWindow` |
| T17 | A replay's order asks for every selected event's dedupe rows to be extended for the full retention, and the statement does it for every consumer the table holds, never shortening one | EXISTS | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aReplayRequeuesAndExtendsTheDedupe`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterStatementsTest.java#theDedupeIsExtended` |
| T17 | A retry at or past the dedupe window is refused, because nothing proves an earlier attempt never reached the broker, unless every consumer of the type is naturally idempotent; a retry inside it extends the dedupe rows | EXISTS | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aRetryPastTheWindowIsRefused`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aRetryOfANaturallyIdempotentTypePasses` |
| T17 | A retry or a discard judges the replayed rows it sets moving: inside the window extended, past it withdrawn and not sent again | EXISTS | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aRetrySettlesTheReplaysBehindIt`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aDiscardSettlesTheReplaysBehindIt` |
| T17 | On a real database: the window on the server's clock, the extension in the order's own transaction, the row locks and ten instances racing (`DeadLetterDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T17 | The inbox sweep selects a record only past both its written expiry and the retention in force, with a truth table over a record younger than either (read from the migration; on PostgreSQL, the next row). Its bound is the consumer's own retention, an instance refuses to start with that retention at or below the oldest record the broker can redeliver, the function refuses such a bound too, and that floor is held equal to the topic catalogue | EXISTS | `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#theInboxSelectsOnBothBounds`; `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#anInboxRecordYoungerThanItsBoundIsNeverSelected`; `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#theFunctionsRefuseABoundInsideTheBrokersHorizon`; `app/src/test/java/com/finapp/app/eventing/RetentionSweepScheduleTest.java#theInboxBoundIsTheConsumersRetention`; `app/src/test/java/com/finapp/app/eventing/RetentionSweepScheduleTest.java#aRetentionInsideTheBrokersHorizonRefusesToStart`; `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#theSweepsFloorIsTheOldestRecordTheBrokerCanRedeliver` |
| T17 | The same selection on PostgreSQL, a row older than the bound deleted and a younger one kept (`RetentionSweepDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T17 | One effect per key counted after a rolling update and a pod kill | PLANNED | `P15-TSK-012` |
| T18 | A manual adjustment's approver differs from its proposer, as a `CHECK` every writer meets (`INV-AUD-04`) | EXISTS | `ledger/src/test/java/com/finapp/ledger/AdjustmentProposalMigrationTest.java#theFourEyesCheckIsTheInvariants` |
| T18 | Where the vocabulary names an act's proposal and approval as two permissions (six pairs today), no role holds both | EXISTS | `identity/src/test/java/com/finapp/identity/RoleNameTest.java#noRoleProposesAndApprovesTheSameAct` |
| T18 | Where one role holds both halves of a desk, the domain and the database still refuse the proposer's own approval: a loan fee waiver and a repayment reversal (lending servicing), a BNPL dispute above its threshold (BNPL servicing), a risk case's disposition (risk) | EXISTS | `lending/src/test/java/com/finapp/lending/FeeAndWaiverDatabaseTest.java#selfApprovalRefusedAtBothRanks`; `lending/src/test/java/com/finapp/lending/RepaymentReversalDatabaseTest.java#selfApprovalRefusedAtBothRanks`; `bnpl/src/test/java/com/finapp/bnpl/BnplDisputeDatabaseTest.java#selfApprovalRefusedAtBothRanksAndAudited`; `risk/src/test/java/com/finapp/risk/CaseDatabaseTest.java#theProposerCannotApprove` |
| T18 | The adjustment refuses self-approval, and the refusal is recorded in its own transaction | EXISTS | `ledger/src/test/java/com/finapp/ledger/AdjustmentProposalTest.java#selfApprovalIsRefusedByTheAggregate`; `app/src/test/java/com/finapp/app/ledger/LedgerAdjustmentsRefusalTest.java#aRefusedSelfApprovalIsRecordedInItsOwnTransaction` |
| T18 | A period close's proposer cannot approve it | EXISTS | `accounting/src/test/java/com/finapp/accounting/PeriodClosesTest.java#theProposerCannotApprove` |
| T18 | A break's resolution under `MULTI_FACTOR`, and never one person's word for an aggregate subject | REMEDIATION | `P14-REM-001` |
| T18 | Step-up on the mapping activation and the acknowledgement approval | REMEDIATION | `P14-REM-002` |
| T18 | The privileged access review names, for each of the six propose/approve pairs, the two roles no identity holds together, and its partition is the code's | EXISTS | `app/src/test/java/com/finapp/app/architecture/PrivilegedAccessReviewTest.java#thePartitionIsTheCodes` |
| T18 | Role assignment refusing an identity both roles of a propose/approve pair (today an administrator can grant it, audited; each act still refuses its proposer by identity) | GAP | `PI-020`; owner: the owner, since 2026-11-08 |
| T19 | An owner-scoped statement carries the owner predicate, and each owner-scoped operation has a named negative test | EXISTS | `app/src/test/java/com/finapp/app/architecture/OwnershipIsScopedTest.java#ownerScopedStatementsCarryThePredicate`; `app/src/test/java/com/finapp/app/architecture/OwnershipIsScopedTest.java#ownerScopedOperationsHaveNegativeTests` |
| T19 | A session-derived endpoint takes no identifier | EXISTS | `app/src/test/java/com/finapp/app/architecture/OwnershipIsScopedTest.java#sessionDerivedEndpointsTakeNoIdentifier` |
| T19 | Every merchant route the application serves is in the battery's derived table, each addressed route carrying a cross-tenant probe (in the battery or a named delegate); the battery's own probes answer another tenant's resource exactly as an unknown one (`INV-MER-01`) | EXISTS | `app/src/test/java/com/finapp/app/merchant/MerchantTenancyBatteryDatabaseTest.java#theTableIsEveryMerchantRoute`; `app/src/test/java/com/finapp/app/merchant/MerchantTenancyBatteryDatabaseTest.java#everyAddressedRouteAnswersAnotherTenantsResourceAsUnknown` |
| T19 | Every handler declares its rule, and an undeclared endpoint is refused | EXISTS | `app/src/test/java/com/finapp/app/api/EveryEndpointDeclaresARuleTest.java#everyHandlerDeclaresSomething`; `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#anUndeclaredEndpointIsRefused` |
| T19 | The ownership detector widened to every module | REMEDIATION | `X-TSK-002` |
| T20 | A session token appears in no column of its stored row | EXISTS | `app/src/test/java/com/finapp/app/domain/SessionLifecycleDatabaseTest.java#theTokenIsNeverStored` |
| T20 | Revocation is immediate across instances (`INV-IDN-03`) | EXISTS | `app/src/test/java/com/finapp/app/domain/SessionRevocationDatabaseTest.java#revocationIsImmediateAcrossInstances` |
| T20 | A revoked merchant API key is refused immediately | EXISTS | `app/src/test/java/com/finapp/app/merchant/MerchantApiKeyDatabaseTest.java#aRevokedKeyRefusesImmediately` |
| T20 | The published contract carries a secret-named member only in a request body: never in a response, a path, a query or a header | EXISTS | `app/src/test/java/com/finapp/app/domain/CredentialReachesNoEmittedSinkTest.java#thePublishedContractCarriesASecretOnlyInARequestBody` |
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
| T25 | Every topic a produced event names is declared, the producers read from the compiled code, so a broker that creates no topic on first use stalls no aggregate | EXISTS | `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#everyProducedTopicIsDeclared`; `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#everyProducerIsReadable` |
| T25 | The broker posture as code: no topic created on first use, no unclean leader election, three replicas with two in sync, and the consumers' session timeout inside the broker's bounds | EXISTS | `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#theBrokerPostureHolds`; `app/src/test/java/com/finapp/app/architecture/TopicCatalogueTest.java#theSessionTimeoutIsAdmitted` |
| T25 | A consumer the broker refuses fails the instance's readiness and never its liveness; a failure that is not a refusal fails neither | EXISTS | `app/src/test/java/com/finapp/app/eventing/KafkaConsumersReadinessTest.java#readinessAndNotLiveness`; `app/src/test/java/com/finapp/app/eventing/KafkaConsumersHealthIndicatorTest.java#aRefusalIsDown`; `app/src/test/java/com/finapp/app/eventing/InboxConsumersTest.java#aFailureIsNotARefusal` |
| T25 | A failed TLS handshake is no refusal - a broker-side certificate fault meets every replica at once, so it fails no readiness - and is counted with every thrown poll; a refusal clears only on evidence of admission, not on the next quiet poll | EXISTS | `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverConfigurationTest.java#aRefusalIsTranslated`; `app/src/test/java/com/finapp/app/eventing/InboxConsumersTest.java#aQuietPollDoesNotClearARefusal`; `app/src/test/java/com/finapp/app/eventing/InboxConsumersTest.java#theCountersAreEager` |
| T25 | An alert on thrown consumer polls (`finapp.inbox.poll.failures`), and a series of its own for a consumer evicted past its poll budget, the client's commit failure translated so no Kafka type leaves the receiver | EXISTS | `app/src/test/java/com/finapp/app/eventing/InboxConsumersTest.java#anEvictionIsCountedApart`; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverTest.java#anEvictionIsTranslated`; `infra/prometheus/rules/platform.yml` `contains: finapp_inbox_poll_failures_total{outcome="failed"}` |
| T25 | Three brokers across three zones with `min.insync.replicas` 2 and replication 3, taken from `infra/kafka/broker.properties` and the catalogue, unclean election off; a broker can be lost without losing an acknowledged record or refusing writes. Asserted over a plan against mock providers, never applied | EXISTS | `infra/terraform/modules/kafka/tests/kafka.tftest.hcl` `contains: There are not three brokers.` `contains: min.insync.replicas is not 2: an acknowledged record could live on one broker.` `contains: Unclean leader election is on` `contains: A topic does not keep three copies with two in sync before an acknowledgement.` |
| T25 | Event freshness and consumption objectives with fast and slow burn-rate pairs, and an outbox dead letter paging on its first sample | EXISTS | `app/src/test/java/com/finapp/app/telemetry/AlertCatalogueTest.java#everyObjectiveHasBothPairs`; `app/src/test/java/com/finapp/app/telemetry/AlertCatalogueTest.java#theCorrectnessSignalsPageOnTheirFirstSample` |
| T25 | The broker outage procedure, rehearsed | PLANNED | `P15-TSK-032` |
| T26 | In a customer's own account flows (the sweep's), every audit record names that customer as actor, carries operation, target and outcome, and joins to its request by correlation | EXISTS | `app/src/test/java/com/finapp/app/domain/AuditNamesTheActorDatabaseTest.java#authenticatedRequestsNameThePerson`; `app/src/test/java/com/finapp/app/domain/AuditNamesTheActorDatabaseTest.java#everyRecordCarriesTheAuditFields` |
| T26 | The auditable action registry and its catalogue agree | EXISTS | `app/src/test/java/com/finapp/app/audit/AuditableActionRegistryTest.java#theRegistryAndTheCatalogueAgree` |
| T26 | Audit retention and its archival design are written: no audit record is deleted, a jurisdiction's period stays open, and the archival is a verified copy (the guard holds what the policy states) | EXISTS | `app/src/test/java/com/finapp/app/architecture/RetentionPolicyIsCompleteTest.java#auditIsNeverDeletedAndItsArchivalIsACopy`; `app/src/test/java/com/finapp/app/architecture/RetentionPolicyIsCompleteTest.java#everyJurisdictionDependentPeriodIsOpen` |
| T27 | The application image carries the OCI `revision` (the commit, marked `-dirty` from a changed tree), `source` and `version` labels, passed by the build script and read back from the built image by CI's image job | EXISTS | `app/src/test/java/com/finapp/app/architecture/ApplicationImageTest.java#theImageCarriesItsRevisionSourceAndVersion`; `infra/scripts/image-build.sh` `contains: label org.opencontainers.image.$key is` |
| T27 | Build identity asserted after deploy, against the promoted image's revision | PLANNED | `P15-TSK-017` |
| T27 | Signatures with provenance and SBOM attestations, verified at admission | PLANNED | `P15-TSK-007` |
| T27 | A registry with immutable tags and scan on push | PLANNED | `P15-TSK-016` |
| T27 | Deploy by digest, with promotion refusing a tag or an unsigned digest | PLANNED | `P15-TSK-017` |
| T28 | A second approver on the production overlay through `CODEOWNERS`, a promotion script demanding staging evidence, pull-based GitOps | PLANNED | `P15-TSK-017` |
| T28 | No standing human write access in production, and break-glass time-bound | PLANNED | `P15-TSK-010` |
| T28 | Branch protection and the production environment's required reviewers, which only the owner can apply | GAP | `owner item 4`; owner: the owner, since 2026-11-08 |
| T29 | Read-only roles for humans, an audited, time-bound break-glass role, no `get secrets` | PLANNED | `P15-TSK-010` |
| T29 | The cluster's API endpoint has no public address, its audit log is on with every other control-plane log and goes to a group with deletion protection, and creating the cluster grants its creator no standing access: asserted over a plan against a mock provider, never applied. Production and staging are separate root modules, each with its own state key and a provider confined to its own account | EXISTS | `infra/terraform/modules/kubernetes/tests/kubernetes.tftest.hcl` `contains: The API endpoint has a public address.` `contains: The control plane's audit log is off.` `contains: The cluster's creator gains standing administrator access, or the legacy aws-auth path is open.`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#ciRunsEveryCheckOverTheTreeAndItsPlants`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#everyEnvironmentKeepsItsStateRemoteLockedAndEncrypted`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#everyEnvironmentNamesAndTagsThroughTheConventions` |
| T29 | Workload identities, KMS key policies, separate accounts and keys per environment, and the cluster's access entries, the only way any principal reaches its API | PLANNED | `P15-TSK-016` |
| T29 | Break-glass and compromise procedures rehearsed | PLANNED | `P15-TSK-032` |
| T29 | A human IAM baseline for the cloud account: MFA required by policy, no long-lived human access keys, permission boundaries, asserted by `terraform test` with mock providers | PLANNED | `P15-TSK-016` |
| T30 | CI's token is read-only in every permissions entry, no job references a secret, and the workflow is the repository's only one, with no local action | EXISTS | `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#theTokenIsReadOnly`; `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#noJobReferencesASecret`; `app/src/test/java/com/finapp/app/architecture/CiWorkflowIsLockedDownTest.java#thereIsOneWorkflow` |
| T30 | Pull-based GitOps: no CI credential reaches production | PLANNED | `P15-TSK-017` |
| T30 | No cloud credential and no state backend in CI's infrastructure checks. The `iac` job declares no environment, permission or secret of its own. Its script initialises only with `-backend=false`, runs no command that touches a backend, a state or an account, gives no container a host variable, and mounts the tree read-only. Every `terraform test` file mocks every provider its directory requires and configures no real one. No backend or provider block holds a credential argument | EXISTS | `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#ciHoldsNoCredentialAndInitialisesNoBackend`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#everyTestMocksEveryProvider`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#everyEnvironmentKeepsItsStateRemoteLockedAndEncrypted` |
| T30 | Keyless signing from CI's identity, its trust narrowed at admission | PLANNED | `P15-TSK-007` |
| T31 | No handler is served without a declared rule, and every permission-guarded route requires exactly the permission the register names | EXISTS | `app/src/test/java/com/finapp/app/api/RoutePermissionRegisterTest.java#everyGuardedRouteRequiresItsRegisteredPermission`; `app/src/test/java/com/finapp/app/api/EveryEndpointDeclaresARuleTest.java#everyHandlerDeclaresSomething` |
| T31 | The public allow-list generated from the permission register, with an operator route refused on the public host | PLANNED | `P15-TSK-011` |
| T31 | Every route under `/v1/operator` declares a permission, read from the handler mapping that serves requests; a planted operator route declaring only a session is caught | EXISTS | `app/src/test/java/com/finapp/app/api/RoutePermissionRegisterTest.java#everyOperatorRouteIsPermissionGuarded`; `app/src/test/java/com/finapp/app/api/RoutePermissionRegisterTest.java#anUnguardedOperatorRouteIsCaught` |
| T32 | Point-in-time recovery in every environment (7 to 35 days, anything else refused), automated backups kept when the instance is deleted, a final snapshot, deletion protection, `prevent_destroy` on the database and its vault, and a daily snapshot copied across an account or region boundary, where it takes the destination vault's key. Asserted over a plan against a mock provider, never applied: no backup has been taken or restored. What this does not give is the `GAP` row below | EXISTS | `infra/terraform/modules/postgres/tests/postgres.tftest.hcl` `contains: Point-in-time recovery does not reach back as far as the root asked.` `contains: Deleting the instance would delete its automated backups.` `contains: The database has no deletion protection.` `contains: The daily snapshot does not go to this vault and on to the destination across the boundary.` `contains: a_copy_vault_inside_this_account_and_region_is_refused`; `app/src/test/java/com/finapp/app/architecture/TerraformIsPinnedAndCheckedTest.java#everyGuardedResourcePreventsItsDestruction` |
| T32 | A backup under a key separate from the database's own. RDS gives no setting for it, and AWS Backup's vault key does not change an RDS recovery point inside one account and region, so a vault key that differs (asserted) does not by itself separate anything. Only the copy across the boundary does, at a daily recovery point, and its destination does not exist. A point-in-time backup under a separate key needs cross-region replication, Phase 16's | GAP | `owner item 23`; owner: the owner, since 2026-11-09 |
| T32 | A restore rehearsed into a fresh database, ending in the financial verification, with the backup handled as a copy of the ledger | PLANNED | `P15-TSK-030` |
| T33 | Locally (compose and the test databases), the migrator is `NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT`, and no later statement alters or grants that away | EXISTS | `app/src/test/java/com/finapp/app/architecture/LocalDatabaseRolesAreUnprivilegedTest.java#everyRoleIsUnprivileged` |
| T33 | The journal's append-only trigger binds the migrator | EXISTS | `app/src/test/java/com/finapp/app/domain/JournalPostingDatabaseTest.java#theAppendOnlyTriggerBindsTheMigrator` |
| T33 | The migrator image holds no credential (no environment of its own, read from the built image), the application's configuration never reads the migrator's, and the runner refuses an identity holding any administrative right, a session without its lock bound and a URL that could move the host or undo verification | EXISTS | `migrator/src/test/java/com/finapp/migrator/MigratorImageTest.java#onlyTheMigratorHoldsItsCredential`; `migrator/src/test/java/com/finapp/migrator/ConnectedRoleTest.java#anAdministrativeRightIsRefused`; `migrator/src/test/java/com/finapp/migrator/ConnectedRoleTest.java#aMembershipIsARight`; `migrator/src/test/java/com/finapp/migrator/ConnectedRoleTest.java#aSessionWithoutTheBoundIsRefused`; `migrator/src/test/java/com/finapp/migrator/DatabaseUrlTest.java#parametersThatChangeWhatTheUrlMeans`; `infra/scripts/migrator-image-build.sh` `contains: the image sets environment of its own` |
| T33 | The deployed roles come from a separate one-time step that alone holds the master credential: the migrator's entrypoint cannot reach it and never reads that credential | EXISTS | `migrator/src/test/java/com/finapp/migrator/RoleBootstrapTest.java#theBootstrapIsNeverPartOfTheJob`; `migrator/src/test/java/com/finapp/migrator/RoleBootstrapTest.java#theMasterCredentialIsItsOwn` |
| T33 | The migrator Job's secret reference and the absence of any `command` of its own, in its manifest | PLANNED | `P15-TSK-012` |
| T33 | The migrator's egress reaches PostgreSQL alone | PLANNED | `P15-TSK-010` |
| T33 | The migrator's identity reads only its own secret | PLANNED | `P15-TSK-016` |
| T33 | Every reconciliation and settlement table refuses `TRUNCATE` to every role, the migrator and the owner included, by a statement trigger of its own, as its row triggers refuse `DELETE`; a guard over every module's migrations finds any other table that refuses `DELETE` and not `TRUNCATE` | EXISTS | `app/src/test/java/com/finapp/app/architecture/OwnerReachOverHistoryIsClosedTest.java#everyTableThatRefusesDeleteRefusesTruncate`; `app/src/test/java/com/finapp/app/architecture/OwnerReachOverHistoryIsClosedTest.java#theGuardReadTheChain`; `infra/scripts/migrator-verify.sh` `contains: was not refused by its own trigger` |
| T33 | 51 tables in eight other schemas that refuse `DELETE` by trigger do not refuse `TRUNCATE`, the journal's two (`ledger.journal_entry`, `ledger.journal_line`) among them, each named by the same guard | PLANNED | `P15-TSK-040` |
| T34 | The payment-evidence and KYC-document ciphers' tests show the stamped key version on a ciphertext; the other six ciphers stamp it by construction, untested here | EXISTS | `payments/src/test/java/com/finapp/payments/EvidenceCipherTest.java#roundTrips`; `kyc/src/test/java/com/finapp/kyc/DocumentCipherTest.java#roundTrips` |
| T34 | Another key never decrypts an MFA secret, a KYC document or payment evidence | EXISTS | `identity/src/test/java/com/finapp/identity/SecretCipherTest.java#anotherKeyCannotDecrypt`; `kyc/src/test/java/com/finapp/kyc/DocumentCipherTest.java#wrongKeyIsRefused`; `payments/src/test/java/com/finapp/payments/EvidenceCipherTest.java#tamperingAndWrongKeyRefuse` |
| T34 | Data keys wrapped by KMS keys with deletion waiting periods | PLANNED | `P15-TSK-016` |
| T34 | Key rings: one active version, the retained versions still decrypting | PLANNED | `P15-TSK-019` |
| T34 | Key rotation rehearsed | PLANNED | `P15-TSK-020` |
| T35 | Settlement file chunks and counterparty screening content are bound to their row, so a moved ciphertext fails | EXISTS | `settlement/src/test/java/com/finapp/settlement/SettlementFileCipherTest.java#theBindingHolds`; `kyc/src/test/java/com/finapp/kyc/CounterpartySubjectCipherTest.java#theRowIsBound` |
| T35 | Associated data bound in the five ciphers that bind none | REMEDIATION | `X-TSK-010` |
| T36 | `verify-full` required to a database off loopback | EXISTS | `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#remoteWithoutTlsIsRefused`; `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#weakerModesAreRefused` |
| T36 | A URL the database driver reads as another host - its `host` or `PGHOST` query parameter, an unbracketed IPv6 entry - is not loopback, so it needs `verify-full` | EXISTS | `app/src/test/java/com/finapp/app/security/TransportSecurityGuardTest.java#aHostTheDriverReadsElsewhereNeedsTls` |
| T36 | The server refuses a connection without TLS (`rds.force_ssl`, through a parameter group the instance is asserted to use); the database has no public address and sits on the workload subnets. Asserted over a plan against a mock provider, never applied; CI's check refuses a plant of each | EXISTS | `infra/terraform/modules/postgres/tests/postgres.tftest.hcl` `contains: The server accepts a connection without TLS: rds.force_ssl is not 1.` `contains: The instance does not use this parameter group, so none of its parameters apply.` `contains: The database has a public address.`; `infra/terraform/environments/production/tests/production.tftest.hcl` `contains: Production's database is not on exactly the workload subnets.` |
| T36 | Egress to PostgreSQL only from the application's and the migrator's pods | PLANNED | `P15-TSK-010` |
| T37 | Outbox and inbox retention sweeps: batched under `SKIP LOCKED`, selecting on an allow-list that never takes a non-terminal row or a row a standing parked record names, an unreviewed inbox column stopping the sweep, each predicate indexed, and deletion by two definer functions alone (read from the migration; on PostgreSQL, the next row) | EXISTS | `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#aNonTerminalRowIsNeverSelected`; `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#anOutboxRowAStandingParkedRecordNamesIsNeverSelected`; `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#anUnknownStateIsNeverSelected`; `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#eachSweepIsBatchedAndDisjoint`; `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#theIndexesServeThePredicates`; `platform/src/test/java/com/finapp/platform/retention/RetentionSweepMigrationTest.java#deletionIsTheFunctionsAlone` |
| T37 | Ten sweepers deleting disjoint batches, counted on PostgreSQL (`RetentionSweepDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T37 | Growth of the audit, idempotency and session tables: not swept, by ruling | GAP | `owner item 18`; owner: the owner, since 2026-11-08 |
| T38 | A permission denial is audited | EXISTS | `app/src/test/java/com/finapp/app/domain/DenyByDefaultDatabaseTest.java#aDenialIsAudited` |
| T38 | An operator act is recorded `EMPLOYEE`, decided per request from the server's record of roles and never from the client; a customer refused at an operator route `CUSTOMER` | EXISTS | `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#anOperatorActIsTypedEmployee`; `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#aRefusalIsTypedByWhoTried`; `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#aRoleHolderOnACustomerRouteIsACustomer` |
| T38 | A refused step-up is recorded once per refused request, committed on its own, naming the route pattern and no session, code or factor | EXISTS | `app/src/test/java/com/finapp/app/session/OperatorActorTypingTest.java#aRefusedStepUpIsRecorded`; `app/src/test/java/com/finapp/app/session/StepUpRefusalsTest.java#anOperationsRefusalIsRecordedOnce` |
| T38 | Refused and failed Phase 7 and Phase 8 acts (the refused four-eyes attempts among them) and failed or not-found Phase 9 and 10 operator reads recorded with their outcome, and a merchant's dispute act naming its API key (returned to the owner, 2026-11-08) | PLANNED | `P15-TSK-039` |
| T39 | The operator door retries an abandoned event, recording who and why; a standing discard stops the retry | EXISTS | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aRetryReturnsTheEventToTheRelay`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aRetryIsRefused` |
| T39 | A discard, of an abandoned event or a parked record, needs a second person, judged by who they are and never by their type; the row stays, with the decision recorded | EXISTS | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aDiscardNeedsASecondPerson`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#theSamePersonIsTheSamePersonWhateverTheirType`; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterOperationsTest.java#aParkedRecordIsDiscardedByTwoPeople` |
| T39 | Every dead-letter door is `EVENT_OPERATE`, and every act among them is keyed and on a `MULTI_FACTOR` session; a refused act is recorded `DENIED` in a transaction of its own | EXISTS | `app/src/test/java/com/finapp/app/eventing/DeadLetterDeskTest.java#theDoorsAreDeclared`; `app/src/test/java/com/finapp/app/eventing/DeadLetterDeskTest.java#aRefusalIsRecorded` |
| T39 | The relay's abandonment is recorded as the platform, naming the event and its flow; every declared action is referenced by production code | EXISTS | `platform/src/test/java/com/finapp/platform/outbox/AuditedAbandonmentsTest.java#anAbandonmentIsRecorded`; `app/src/test/java/com/finapp/app/audit/AuditCompletenessTest.java#everyActionIsEmittedOrDeclaredNotToBe` |
| T39 | On a real database: the relay writes that record in the transaction that abandons the row, exactly once, and a discarded row no longer blocks its aggregate (`DeadLetterDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T39 | A poison record is parked once its budget is spent, its partition continuing, the offset moving only after the park's commit; a transient failure is never counted, a production store's included, whose exception names its SQLState only in text | EXISTS | `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverParkingTest.java#aPoisonRecordParksAfterItsBudget`; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverParkingTest.java#aParkedRecordDoesNotBlockItsPartition`; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverParkingTest.java#theOffsetMovesOnlyAfterTheParkIsDurable`; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverParkingTest.java#aTransientFailureNeverParks`; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaEventReceiverParkingTest.java#aStoresTransientFailureNeverParks`; `app/src/test/java/com/finapp/app/eventing/StoreFailuresAreClassifiedTest.java#everyStoreIsClassified` |
| T39 | On a real database: the count held durably and the budget decided in one statement on the row, across connections and restarts (`DeadLetterDatabaseTest`, written and owed) | PLANNED | `P15-TST-002` |
| T40 | The edge sets the client address, and only the edge's is trusted | PLANNED | `P15-TSK-011` |
| T40 | Per-source limits keyed on the address the edge sets | PLANNED | `P15-TSK-022` |
| T41 | A key reused with a different request is refused as a distinct conflict, never replayed | EXISTS | `platform/src/test/java/com/finapp/platform/idempotency/IdempotentExecutorTest.java#differingFingerprintIsRejected` |
| T41 | Every idempotency claim scoped to its principal | REMEDIATION | `X-TSK-003` |
| T42 | A managed registry with immutable tags | PLANNED | `P15-TSK-016` |
| T42 | Nothing in Phase 15 caches or mirrors images for a replica starting while the registry is down: accepted until 2027-06-30 and re-owned to Phase 16's disaster recovery | GAP | `owner item 1`; owner: the owner, since 2026-11-08 |
| T43 | The FX and credit providers' wire vocabularies are confined to their adapters by build rules, each with a planted leak caught | EXISTS | `app/src/test/java/com/finapp/app/architecture/FxProviderVocabularyIsConfinedTest.java#theVocabularyLivesInTheAdapterAlone`; `app/src/test/java/com/finapp/app/architecture/CreditProviderVocabularyIsConfinedTest.java#theVocabularyLivesInTheAdapterAlone` |
| T43 | The regulatory filing adapter: a malformed or oversized answer decides nothing | EXISTS | `accounting/src/test/java/com/finapp/accounting/RegulatoryFormTest.java#aMalformedAnswerDecidesNothing`; `accounting/src/test/java/com/finapp/accounting/RegulatoryFormTest.java#anOversizedAnswerDecidesNothing` |
| T43 | Static analysis over every module's production source, the adapters included, in CI's `sast` job | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#theStaticAnalysisRunsOverTheVendoredRulesOffline`; `infra/scripts/sast.sh` `contains: /repo/*/src/main` |
| T43 | Static analysis flags a provider's answer read with no bound (a body handler reading it whole, or `readAllBytes`), with a planted case refused by name in every run | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#theUnboundedBodyRuleIsVendoredAndPlanted`; `infra/semgrep/rules/provider-body.yml` `contains: finapp-provider-body-unbounded`; `infra/scripts/sast.sh` `contains: refuses unbounded-body finapp-provider-body-unbounded` |
| T43 | The sixteen reads of a provider's answer the rule found, each read whole and then checked against a size bound, carried in `infra/semgrep/baseline.tsv` with a reason and an expiry; a read bounded before it allocates is for the first real provider integration; accepted by the owner on 2026-11-09, until 2027-06-30 | GAP | `owner item 12`; owner: the owner, since 2026-11-08 |
| T44 | Report generation reaches no writer, and no write path, by any route the build can see (`INV-ACC-14`) | EXISTS | `app/src/test/java/com/finapp/app/architecture/ReportingNeverWritesTest.java` |
| T44 | Report generation's snapshots open connections from the reporting pool alone, as `finapp_reporting`; with the pool unreachable no content is produced, no run completes and the application's pool is never asked | EXISTS | `app/src/test/java/com/finapp/app/accounting/ReportGenerationReadsAsTheReportingRoleTest.java#aRunsGenerationFailsClosedAtTheReportingPool`; `app/src/test/java/com/finapp/app/accounting/ReportGenerationReadsAsTheReportingRoleTest.java#aRegenerationReadsAsTheReportingRole` |
| T44 | The reporting role holds `SELECT` on the report run's tables and nothing else | EXISTS | `app/src/test/java/com/finapp/app/architecture/ReportingRoleIsSelectOnlyTest.java#theRoleReadsTheRegisterAndNothingElse`; `app/src/test/java/com/finapp/app/architecture/ReportingRoleIsSelectOnlyTest.java#theRuleCanFail` |
| T45 | CI's `sast` job refuses a statement whose text is built from anything but constants, in every module's production source: a parameter, a local given a call's result, a field, a loop or lambda variable, a builder, a call, each proven by an annotated line in the rule's own test, and a planted concatenation and a helper handed a built string refused by name in every run. Constant composition (text blocks, `static final` parts, ternaries of them, a private helper given only constants) passes. | EXISTS | `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#theStatementRuleIsVendoredAndPlanted`; `app/src/test/java/com/finapp/app/architecture/SecurityScansAreWiredTest.java#theBaselineIsSmallAndReasoned`; `infra/semgrep/rules/sql.yml` `contains: finapp-sql-text-not-constant` `contains: finapp-sql-helper-given-non-constant`; `infra/scripts/sast.sh` `contains: refuses sql-concatenation finapp-sql-text-not-constant` |
| T45 | The statements the rule cannot prove constant, each with a reason and an expiry in `infra/semgrep/baseline.tsv`; accepted by the owner on 2026-11-09, until 2027-06-30 | GAP | `owner item 12`; owner: the owner, since 2026-11-08 |
| T46 | A field or accessor named for a secret must be held in `Sensitive`, and only the correlation context writes the logging context (the build rules) | EXISTS | `app/src/test/java/com/finapp/app/architecture/NoUnwrappedSecretRulesTest.java#secretsAreWrapped`; `app/src/test/java/com/finapp/app/architecture/NoUnwrappedSecretRulesTest.java#onlyCorrelationContextWritesTheMdc` |
| T46 | A `Sensitive` value is masked on every rendering path and on both appenders | EXISTS | `app/src/test/java/com/finapp/app/observability/LogRedactionTest.java#everyRenderingPathIsMasked`; `app/src/test/java/com/finapp/app/observability/RedactionAcrossAppendersTest.java#redactionHoldsOnBothAppenders` |
| T46 | No meter carries a tag whose value a request could influence | EXISTS | `app/src/test/java/com/finapp/app/telemetry/MetricConventionTest.java#tagsCannotCarryUnboundedValues` |
| T46 | An unexpected exception's message, type and stack frames never reach an error body; a malformed body is not echoed back; no error body carries a member beyond the contract | EXISTS | `app/src/test/java/com/finapp/app/api/ApiErrorHandlerTest.java#anUnexpectedExceptionLeaksNothing`; `app/src/test/java/com/finapp/app/api/ApiErrorHandlerTest.java#aMalformedBodyRendersTheContract`; `app/src/test/java/com/finapp/app/api/ApiErrorHandlerTest.java#theShapeIsExactlyTheContract` |
| T46 | One collector as the telemetry egress, its span attributes an allow-list, a scrubbing stage, and the needle sweep over a rehearsal's exported spans and logs | PLANNED | `P15-TSK-023` |
| T47 | A cluster-safe registration throttle per source, which bounds how fast identifiers can be tested | PLANNED | `P15-TSK-022` |
| T47 | Registration answers a taken identifier `422` by design (accepted); verify-then-tell, the alternative, waits for the first real customer launch | GAP | `PI-017`; owner: the owner, since 2026-11-08 |

**Where the evidence is a database test.** Many `EXISTS` rows cite tests in the `database` tier. Phase 15's standing
deviation (C14) defers those tiers to one pass at the phase exit (`P15-TST-002`). Each such test exists and was green
at its own task's gate or at the last database pass. This register claims that the test exists and holds the stated
property. It does not claim a fresh run on this tree.

---

## 5. Residuals and gaps

What a threat's controls leave open, stated so it is not mistaken for coverage. Where no task owns a residual, a
`GAP` row in §4 and an item in §6 hold it.

- **T15, the raw writer.** The database refuses an unbalanced entry and any edit of the journal (and, after
  `P14-REM-004`, the Phase 14 shapes). But every module shares one role, `finapp_app`, which holds `INSERT` on the
  journal and `UPDATE (posted_minor, holds_minor, last_entry_seq, updated_at)` on the balance projection (ledger
  `V006`). **The continuous trial balance never detects a balanced entry**: a balanced entry is exactly what it sums
  to zero. GL = ledger compares two views of the same journal. Rule conformance judges an entry's namespace and shape,
  never its amount or its provenance (ADR-0117). So, for an entry inserted by anyone holding the application's
  credential:
  - **Refused at insert or commit**, raw SQL included: an unbalanced entry and one of fewer than two lines (`V004`), a
    line on an account that is not active (`V007`), a reversal beyond its original (`V009`), an `ADJUSTMENT` entry no
    approved two-person proposal names (`V010`), an entry or line dated on or before the posting cutoff (`V028`,
    `V032`), and any edit of the journal. `V015`'s binding (re-stated through `V030`) refuses a `MANUAL`-origin
    adjustment *proposal* line on a reconciled position or a closed book; it judges the adjustment path only, so an
    entry inserted straight into the journal as a `POSTING` never meets it.
  - **Detected**, late, by the close or by the proof's own reading - **and only for a naive raw insert or a code
    defect.** Every proof compares the ledger with the product's own rows, and `finapp_app` holds `INSERT` on those
    rows too (`fx.trade`, `lending.repayment`, `reconciliation.expectation` among them): a holder who writes the row
    first defeats the proof. With that qualification, `AccountPurpose`'s forty members divide three ways. **Read per
    account (28)**: the loan book's six per-loan receivables and credit balance and the BNPL book's six per-obligation
    ones (the subledger proofs; every entry on them must be named by a row), lending capital (its capital proof, and
    the position proof as one of the eleven reconciled positions), the five FX books (fx's books proof), and the
    other ten reconciled positions - the card, instant and payout clearings, the FX provider and corridor clearings,
    cash at bank, suspense, processing costs, reconciliation losses and gains (the position proof's completeness). **Read only through the lines a row names (2)**: loan fee income (the
    subledger proof's fee arm) and BNPL merchant fee income (the three-way proof's fee identity); a line no row names
    is not seen. Also detected: merchant payable lines under a `bnpl.` key (the three-way proof's merchant leg), an
    entry whose namespace no rule registers or whose shape no rule allows (rule conformance, at the month's close),
    and a projection row left stale (the projection check).
  - **Undetected:** a balanced entry of a registered shape, both projection rows co-updated in the same transaction,
    between accounts no proof reads (**10**): **customer wallets**; **merchant payables** apart from `bnpl.`-keyed
    lines (their check sums the payable's own breakdown, which equals its position by construction, so a balanced
    entry cannot fail it); the platform's **fee revenue**; **loan interest income, BNPL interest income and BNPL
    late-fee income**; **loan and BNPL write-off expense**; **chargeback recoverable and dispute costs**. Also
    undetected: a line on loan fee income or BNPL merchant fee income that no row names, and any book above once the
    product's row is forged first. Two examples: one customer's wallet debited and another's credited, then a
    legitimate withdrawal; or fee revenue debited and the payable of a merchant the attacker controls credited, then
    an ordinary payout. Neither raises anything: no proof reads those accounts, reconciliation sees only the
    legitimate withdrawal or payout, and the trial balance stays zero.
  Phase 15's controls lower the likelihood: the credential reaches only the application's pods (`-018`), and the
  database only the cluster's nodes (a security group of its own; `-010` narrows a node to those pods). They do not change the impact. **Owner item 2, decided 2026-11-08**: the
  source-join census (`P15-TSK-035`) reads every book, the ten above among them, and detects an entry with no domain
  record behind it; the preventive fix is postponed to before production use (`PI-016`). An entry whose domain record
  was written first stays neither refused nor detected until `PI-016` lands.
- **T45, injection through hand-written JDBC.** Every statement is hand-written. The codebase's idiom is constant SQL
  with bind parameters, and the statements read for this register were constant. Some concatenate constants
  (`ROW + " WHERE …"`), and about fifteen `createStatement()` calls execute constant or configuration-derived text.
  *(Since 2026-11-08)* CI's static analysis holds the idiom: a statement whose text is built from anything but
  constants fails the `sast` job, and a statement it cannot prove constant takes a baseline row with a reason and an
  expiry (owner item 12). **Its limits**: Semgrep OSS does not follow a value across methods or files, so a private
  helper's SQL-fragment parameter is checked at its call sites, and a constant another file declares is taken at its
  name; the analysis runs over `main` sources only. Its impact is T15's: a caller running SQL as `finapp_app`.
- **T46, telemetry and error bodies.** The redaction is default-deny for values held in `Sensitive`, and the
  correlation context alone writes log fields. A restricted value held in a plain variable and passed straight to a
  log call has no declaration for either rule to inspect (`SECURITY_ARCHITECTURE.md` §Logging). `-023`'s collector
  scrubbing and needle sweep are the second net. The telemetry back ends and their retention do not exist yet.
- **T47, registration enumeration.** `POST /v1/registrations` answers `422` for a taken login identifier, by design: a
  registration endpoint tells you the name is taken. Authentication's paths are equal-cost (`INV-IDN-07`), so the leak
  is registration's alone. `-022`'s throttle bounds the rate and does not remove the oracle. **Accepted by the owner
  on 2026-11-08** (owner item 6); verify-then-tell is recorded for the first real customer launch (`PI-017`).
- **T29, human administrative access.** `-010` bounds what a human can do inside the cluster, and the cluster module
  keeps the API endpoint private and logs what the control plane does (a plan against a mock provider asserts it; no
  account exists to show AWS doing so). Until `-016` adds access entries, no principal can reach the cluster's API at
  all. No task builds the identity controls of a real cloud account: required MFA,
  workforce federation, and no long-lived access keys for humans. **Owner item 3, decided 2026-11-08**: `-016` builds a
  human IAM baseline (MFA required by policy, no long-lived human access keys, permission boundaries) under `terraform
  test`; workforce federation waits for a workforce identity provider (§7, C13).
- **T28.** GitHub's branch protection and environment rules are repository settings, and only the owner can apply
  them (§1.1). `P15-TSK-017` writes them down and adds `CODEOWNERS`. Today no `CODEOWNERS` file exists and no setting
  refuses a single-person change, so "two persons on production" is not yet held by anything. **Owner item 4,
  decided 2026-11-08**: the owner applies them when the repository gains a production environment.
- **T21, T27, T28, T45.** Today **no built control exists** for these CRITICAL and HIGH threats: nothing
  is deployed, and T45's rule is unwritten. Each is answered only by its planned tasks, and rule 9 and the exit rule
  (§1.5) hold them to it. *(Read "T4, T21, T27, T28, T29, T32, T45" until 2026-11-08: T4's image and node controls and
  T29's cluster controls are now built rows of §4, though nothing is deployed; and read with T32 among them until 2026-11-09, when its backup configuration became a built row, though nothing has been backed up.)*
- **T6, T7, T29, the network and cluster modules.** Their rows are asserted over a plan against a mock provider, which
  proves the configuration and not AWS's behaviour: no account exists, so nothing has shown that AWS accepts it or
  enforces it (`PHASE_15_PLAN.md` §1.1). etcd's key is an input each root requires, not yet a key that exists.
- **T7, T9, T10, T15, T25, T32, T36, the data tier.** The same holds of every data-tier row: it is a plan against mock
  providers, and no database, broker or bucket has ever existed. Each engine version, instance class and service limit is
  unverified. **Owner item 23** holds what a plan cannot settle: where a backup under a separate key lives, the audit
  archive's lock, and the path that applies the ACLs.
- **T9.** Off loopback the clients require `SASL_SSL` with a SCRAM identity: `KafkaTransportGuard` refuses
  `SASL_PLAINTEXT`, `SSL`, an unknown protocol and the marked local password *(read "`KafkaTransportGuard` refuses only
  `PLAINTEXT` off loopback; `SASL_PLAINTEXT` is admitted today" until 2026-11-08)*. What remains: the ACLs and the SCRAM
  users are planned from `infra/kafka/topics.yaml` and have never been applied to a broker (**owner item 23**), and no real
  TLS handshake runs until `-012`'s rehearsal. A credential taken from a pod authenticates as the application, whose
  principal may read and write every catalogued topic, so the consumers' discipline is the remaining defence. Not every
  consumer is evidenced as a hint: the earliest consumers act on what an event names, with the platform as actor
  (`SECURITY_ARCHITECTURE.md` §Who is acting). Lending's origination consumers are the ones evidenced as hints here.
- **T7, T34, T35, the field-level ciphers.** Eight AES-256-GCM classes, built as nine instances over eight keys, stamp
  a key version on every row. **Each instance holds one key.** Only `CounterpartySubjectCipher` refuses a mismatched
  stamped version; the other seven fail at the authentication tag. Changing a key today makes every earlier row
  unreadable, which is why rotation waits for key rings (`-019`). **Three classes bind associated data**
  (`CreditEvidenceCipher`, `CounterpartySubjectCipher`, `SettlementFileCipher`). **Five bind none**: `SecretCipher`,
  `DocumentCipher`, `EvidenceCipher`, `PayoutEvidenceCipher` and `FxEvidenceCipher`. `X-TSK-010` names the first four,
  and the fifth is routed onto its entry. Every column outside these ciphers is plaintext in PostgreSQL, relying on volume
  encryption that is configured and has never been applied. `SECURITY_ARCHITECTURE.md` §Encryption at rest has the detail.
- **T2.** `/actuator/health`, `/actuator/info` and `/actuator/prometheus` are still served unauthenticated, now on
  the management port alone: loopback outside Kubernetes, and the pod's own address in the overlays, where
  anything that reaches the pod reaches them until a policy restricts it. Two `CURRENT_STATE.md` debt rows record this, and `-010`'s NetworkPolicy
  closes them. *(Read "served unauthenticated on the API port today" until 2026-11-08, when the port moved.)* *(An empty, not unset, `FINAPP_MANAGEMENT_ADDRESS` would bind the management port to every interface; since 2026-11-08 the startup guard in §4's T2 rows refuses it.)*
- **T14.** The connection budget guard checks the instance count the configuration declares. CI's manifest policy ties
  that declaration to an overlay's maximum replicas before anything is deployed, and `-012`'s measured peak tests the
  rule; nothing compares it with the replicas running at any moment. The rule's `+ 1` is one terminating pod from
  outside the rollout, and a PodDisruptionBudget counts a Ready replacement, not a terminating pod, so a node drain
  taking two pods whose replacements come up quickly can briefly hold one pod more: eight connections, inside the
  twelve reserved. `-012` measures a rollout and a kill, not a drain. No gauge
  reads on the scrape thread any more (2026-11-08), but a scrape still *asks* for reads: a scraper that reaches the
  management port can keep each instance's one gauge thread, and so one of its pool's connections, busy at the floors'
  rate (each gauge at most once per 5 s, each class's own floor beyond that), until a NetworkPolicy restricts that port
  (`-010`). Since 2026-11-08 the port is apart from the API port, loopback outside Kubernetes.
- **T38.** *(Rewritten 2026-11-08: this read "every session actor is audited as `CUSTOMER`, operators included, and a
  refused step-up writes no record".)* An operator act is now recorded `EMPLOYEE` and every refused step-up is recorded.
  **Operator acts recorded before 2026-11-08 read `CUSTOMER` and stay so**: the audit record is append-only and the
  domain's `*_by_type` columns are frozen by trigger, so a report separating staff from customers reads the population
  from the record's date onward, and before it reconstructs it from `identity.role_assignment` and `identity.RoleAssigned`.
  The database cases (an operator act's row, a refused step-up's row) are written and wait for the exit database pass.
  *(Added 2026-11-08 by the carried items' ruling, C15, and returned to the owner the same day:)* refused
  and failed Phase 7 and 8 acts and failed or not-found Phase 9 and 10 operator reads leave no audit outcome;
  the privileged-access task records every refused step-up and every denial, and returned this wider class to
  the owner with the access review's two step-up questions and the conditional step-up's extraction
  (owner item 10), now planned as `P15-TSK-039` (the owner, 2026-11-08).
- **T43.** No general bound on the size of a provider's answer exists across the adapters. The regulatory adapter is
  the one evidenced. The adapters call simulated providers in this programme. **Owner item 5, decided 2026-11-08**:
  static analysis flags an unbounded read of a provider's answer in an adapter, *built 2026-11-08*: the rule refuses a
  new one, and the existing reads are carried in the baseline for the first real provider integration (owner item 12).
- **T31.** The build pins the permission of every route that declares one, refuses a handler that declares no rule,
  and *(since 2026-11-08)* refuses a route under `/v1/operator` that declares no permission. Operator routes outside
  that prefix (identity administration, the ledger's adjustments, KYC review, a payment's refund and void, a transfer's
  reversal) are guarded by permission and pinned by the register, not by the prefix: `P15-TSK-011` keeps `/v1/operator`
  off the public host by path as well as by permission (routed on its entry), and moves the others by permission.
- **T42.** A replica rescheduled while the registry is unreachable cannot pull its image. Nothing in Phase 15 caches
  or mirrors images. **Accepted by the owner on 2026-11-08, until 2027-06-30** (owner item 1), and re-owned to Phase
  16, where registry availability, image pre-pulling and disaster recovery belong.
- **T44, T7.** The reporting role was built (2026-11-08), not cut. Its grants are pinned by a register of the 23 tables a
  report run reads, recounted from the code; whether production's definitions generate within them is the definition
  suites' at the exit database pass, which open their generation snapshots as the role. Every generation reads as it,
  the close's trial-balance check's included; what is not a generation - the operational door, the close checks' own
  judgements and the verdict read back from a stored run, the final statements - reads through the application's role
  in `REPEATABLE READ READ ONLY` snapshots. Startup refuses a reporting user naming the application's role, the
  migrator or the superuser. In a deployed database the role is created by the one-time bootstrap step before the
  migrations that grant to it.
- **T11, the infrastructure.** Its code is pinned, locked and checked on every pull request, and nothing in Phase 15
  applies it. No check reads a real account, so nothing yet holds that an apply comes only from a reviewed plan, and
  nothing reports drift. `prevent_destroy` on the database, its backups and every KMS key is a build rule. It holds
  the database and its backup vault today, and over an empty set for KMS keys until the security module (`-016`) adds the
  first.
  **Owner item 7**, decided 2026-11-08, holds it until a real account exists.
- **T13 and T33, the owner's reach outside lending, reconciliation and settlement.** A guard over every module's migrations
  found the same two holes elsewhere: 51 tables refuse `DELETE` by trigger and not `TRUNCATE`, and 108 functions read a clock
  through a mutable `search_path`. Nothing has happened to them; they are the migrator's or an owner's reach, not a production
  role's. Each is named in that guard, and `P15-TSK-040` closes them (owner item 11, decided 2026-11-09).
- **T13.** Time sync on nodes is a property of the managed cluster. No task asserts it, and the register does not
  claim it, though `InstanceClockOffset` now shows an instance that has drifted. The platform's answer is to decide financial instants on the database's clock. The database's clock steps
  too: a stamp it wrote never decides which of two facts came first, and a stamp follows the facts it must follow.
  Outside the close and the terms, a census guard holds every such read, the deciding ones ranked by the rows, the rest recorded with why; its cases on a stepped clock run in the database pass, and the shapes it cannot read are named in `DISTRIBUTED_EXECUTION.md` section 3 (owner item 8, decided 2026-11-08, built).

---

## 6. Owner items

Only the owner can accept a risk or choose between the options below. **The owner decided items 1 to 6 on 2026-11-08,
accepting each recommendation** ("accept recommendations", after `P15-TSK-001`'s merge); each item's decision follows
its options. *(Read "Each item carries a recommendation, and each is held by a `GAP` row in §4. None blocks Phase 15's
work; the two proposed acceptances (items 1 and 6) must be decided by the phase's exit (§1.5)." until then.)* **Items 7 to 10 were added on 2026-11-08, and the owner decided them the same day, accepting each recommendation**
("accept recommendations", at the end of wave B); each item's decision follows its options. *(This paragraph read
"Item 7 ... awaits the owner" and "Items 9 and 10 ... are open" until then.)* **Items 11, 12, 17 and 18 were added on 2026-11-08, and the owner decided them on
2026-11-09, accepting each recommendation** ("accept recommendations", at the end of wave C); each item's decision follows its
options. *(This paragraph read "Item 11 was added on 2026-11-08 and awaits the owner." until then.)* **Item 27 was added on 2026-11-09 and awaits the owner**; it is held by a `GAP` row of T13.

1. **T42, a proposed acceptance (MEDIUM).** The registry is unavailable when a replica must start, so the rescheduled
   replica cannot pull its image. *Recommendation*: accept it for Phase 15 and re-own it to Phase 16, where registry
   availability, image pre-pulling and disaster recovery belong: `ACCEPTED by the owner on <the date you decide>, until
   <a review date you set>`, for example a review date of 2027-06-30. **Decided 2026-11-08**: `ACCEPTED by the owner on
   2026-11-08, until 2027-06-30`, re-owned to Phase 16; its `GAP` row stays as what stands meanwhile.
2. **T15's residual: a balanced shift between accounts no proof covers is neither refused nor detected.** The options:
   - **(a) An interim detective control.** A source-join census: every journal entry joins, by its `reference` and
     `idempotency_scope`, to the domain record that caused it (the transfer, the payment attempt, the withdrawal, the
     capture, the fee assessment), and that record's amounts equal the entry's lines. **Only the census covers every
     book**, the ten no proof reads among them. A wallet proof (each customer wallet's balance explained by its domain
     records) is the narrower alternative and covers customer wallets only: merchant payables, fee revenue, the
     interest, fee and write-off accounts and the dispute accounts stay unread. Either catches a raw entry with no
     domain record, and a code defect that posts outside the doors. Neither closes the threat, because a credential
     holder can write the domain record first: the same role holds `INSERT` on those tables. **It does not fit
     the correctness-pages task**, which pages on correctness signals that already exist and builds no proof. It needs its own item,
     read-only and changing no posting path, so it could sit inside Phase 15.
   - **(b) The preventive fix.** A definer-rights posting function as the journal's only `INSERT` path, which
     validates each posting against the rule register and the domain record it names; the application role's
     `INSERT` on the journal and its `UPDATE` on the balance projection revoked; and a database role per module, so
     that one module's credential cannot write another's books or rows. It refuses a raw entry with no domain record
     and a shape no rule allows, and it confines an injection or a defect in one module (T45) to that module's books.
     **It does not stop a holder who writes the domain record first**, and the modular monolith's pods hold every
     module's credential, so a compromised pod is narrowed, not stopped: there the defence is the database reachable
     only from those pods (a security group admitting the cluster's nodes, `-010` narrowing a node to them) and the credential delivered only to them (`-018`). It changes every
     module's write path and must be measured on the posting hot path.
   - *Recommendation*: schedule **(b)** as a remediation entry with the horizon "before production use", after Phase
     15, which leaves every posting path as Phases 3–14 built it (`PHASE_15_PLAN.md` §1). Add **(a)** now, as the
     source-join census rather than the wallet proof, as a new Phase 15 item after the correctness pages, so that something reads
     the uncovered books until (b) lands. With (a) and (b) both declined, the residual stays a `GAP` and must be
     accepted by the owner before production use. **Decided 2026-11-08**: (a) is `P15-TSK-035`, the source-join census,
     after the correctness pages; (b) is `PI-016`, postponed by the owner and required before production use.
3. **T29's residual.** No task builds the human identity controls of a real cloud account. *Recommendation*: widen
   `P15-TSK-016` with a human IAM baseline in the `security` module (MFA required by policy, no long-lived human access
   keys, permission boundaries), asserted by `terraform test` with mock providers like the rest of the module.
   Otherwise, record it as a real-environment item under §1.1. **Decided 2026-11-08**: `P15-TSK-016` widened.
4. **T28's residual.** Branch protection and the production environment's required reviewers are the owner's to apply.
   *Recommendation*: apply them when the repository gains a production environment; `-017` writes them down.
   **Decided 2026-11-08**: as recommended; the row stays a `GAP` held by this item until the owner applies them.
5. **T43's residual.** No adapter bounds the size of a provider's answer in general. *Recommendation*: ask CI's
   static analysis to flag an unbounded body read in an adapter, or record it for the first real provider integration.
   **Decided 2026-11-08**: the static analysis flags it (built the same day; see T43's rows in section 4).
6. **T47, a proposed acceptance (MEDIUM).** Registration tells a caller that an identifier is taken, so whether an
   identifier is registered can be learned at the rate `-022`'s throttle allows. The alternative is a business change
   to registration (verify-then-tell, by a channel the identifier's owner controls), which Phase 15 does not make.
   *Recommendation*: accept it, bounded by `-022`'s throttle, and record the alternative for the first real customer
   launch. **Decided 2026-11-08**: `ACCEPTED by the owner on 2026-11-08`; the alternative is `PI-017`.
7. **T11's residual: infrastructure applied only from a reviewed plan, and drift reported.** The infrastructure code
   is pinned, locked and checked on every change, and Phase 15 applies none of it (ADR-0121 decision 6). In a real
   account nothing yet stops a person changing a resource by hand or applying a plan nobody reviewed, and nothing
   reports the drift. Both need an account, and none exists (`PHASE_15_PLAN.md` §1.1). *Recommendation*: record it as
   a real-environment item under §1.1, beside the apply it belongs to. When an account exists, applies run only from a
   pipeline role on the plan a pull request showed, humans hold read-only access to the account (`-016`'s human
   baseline adds the boundaries), and a scheduled plan reports drift. Separately, widen `-017`'s `CODEOWNERS` to the
   production root (`infra/terraform/environments/production/`), so that a change to production's infrastructure code
   needs the same second person as a change to its overlay. **Decided 2026-11-08**: as recommended; recorded under
   `PHASE_15_PLAN.md` §1.1 and `-017`'s `CODEOWNERS` widened; the row stays a `GAP` held by this item until an account exists.
8. **T13's residual: "the latest" chosen by a stamp elsewhere** *(added 2026-11-08, after the six above were decided)*. A backward step of the database's clock made the close read an earlier lock's run as the latest
   (PI-013) and ended a terms version before its offer (PI-014). Both are fixed at their causes, and the close's and the
   terms' readers were censused. Outside them, single-row reads ordering by a stamp had not been judged for whether the row
   each picks decides anything (`grep` counted ten such `ORDER BY <stamp> DESC ... LIMIT 1` reads at the time); risk `V002`'s
   policy retirement was stamped as the terms retirement was, and its comment made the same claim about assessments.
   The options: (a) a census item inside Phase 15, before the database pass: each read classified as deciding or
   displaying, every deciding one ranked by the rows or named by identity, with a clock-step case, and risk `V002`
   given the terms' rule; (b) postpone it, required before production use; (c) accept the residual. *Recommendation*:
   **(a)**, sized like `P15-TSK-035`, after `P15-TST-002`'s dependencies are settled, so its database cases run in the
   pass; if Phase 15 must shrink, (b), never (c), since PI-013 shows one such read can stop a month's close.
   **Decided 2026-11-08**: (a), before the database pass. *Built*: the census guard, every deciding read fixed, risk on the
   terms' rule by a forward migration (T13's rows), the clock-step cases written and owed to the database pass.
9. **T18's residual: one identity may hold both roles of a propose/approve pair** *(added 2026-11-08 by the privileged
   access review)*. `PRIVILEGED_ACCESS_REVIEW.md` §4 names the pairs of roles no identity should hold together
   (`ACCOUNTANT` and `FINANCIAL_CONTROLLER`, `REGULATORY_REPORTER` and `FINANCIAL_CONTROLLER`, `LOAN_SERVICING_AGENT` and
   `LENDING_OFFICER`, `BNPL_SERVICING_AGENT` and `BNPL_OFFICER`). Role assignment does not refuse such a holding: an
   administrator can grant it, with a required, audited reason. Every such act still refuses its proposer by identity,
   at the domain and at the database, so one person cannot propose and approve the same act; holding both roles narrows
   the population. The options: (a) refuse the holding at assignment (a new refusal on `POST
   /v1/identities/{id}/roles`, its answer in the contract, and a check of the holdings that already exist); (b) leave it
   to the review of `PRIVILEGED_ACCESS_REVIEW.md` §5, which finds it. *Recommendation*: (a), as a remediation entry before
   production use, so the rule does not rest on a human review alone; meanwhile (b). Held by a `GAP` row in §4.
   **Decided 2026-11-08**: (a), as `PI-020`, required before production use, with ADR-0071 §10's holding rule; meanwhile (b).
10. **T38's wider class, and two step-up questions, returned to the owner** *(added 2026-11-08 by the privileged access
   review's task at its gate, the carried items' ruling (C15) having routed them to it while it ran)*. Four parts,
   one item: (a) every refused and failed Phase 7 and Phase 8 act - the refused four-eyes attempts (a self-approval, a
   stale echo, a refused kind) among them - audited with its outcome, a merchant's dispute act naming its API key, and the
   void dispatch's missing dispatch record; (b) a failed Phase 9 operator lookup (a `404` on a trace or a provenance), a
   not-found Phase 10 operator read, the credit policy-at-an-instant read and the review queue's served cases recorded,
   and an evidence read's reason screened for a minimum; (c) whether a role that can redirect a merchant's money may be
   held without an enrolled factor, and whether Phase 9's privileged acts (the pricing and corridor policies, the kill
   switches, a trade's reversal) require a multi-factor session; (d) the conditional step-up's 17 production copies
   extracted into one component. **What already holds**: every refused step-up, the 17 copies' included, is recorded at
   the error contract without touching a copy (`identity.StepUpRefused`), and every permission denial is recorded, so
   (d)'s reason is met and only its extraction remains. **Why not taken there**: (a) and (b) are a new audit record at
   each of dozens of doors, each with its own database case, and (c) changes who may act at money-redirecting and Phase 9
   doors, whose suites run against PostgreSQL; none fits the task's gate under C14. *Recommendation*: (c) as
   `PRIVILEGED_ACCESS_REVIEW.md` §6 rules it - a factor required of every holder of `MERCHANT_ADMINISTRATOR`,
   `MERCHANT_PAYOUT`'s and `PAYOUT_DESTINATION_APPROVE`'s roles before the role is granted, and `MULTI_FACTOR` declared on
   Phase 9's approvals, restarts and reversals - and (a), (b) and (d) as **one follow-on M15.7 item** after the privileged
   access task, before the phase's database pass. **Decided 2026-11-08**: as recommended, all four parts in
   `P15-TSK-039`, which also builds (c); T38's row is `PLANNED` on it.
11. **T33's and T13's remainder: the owner's reach outside reconciliation, settlement and lending** *(added 2026-11-08 by the
   task that built the migrator)*. The two debt rows named those three schemas; the guard over every module's
   migrations that the same task wrote found the same two holes in others. **`TRUNCATE`**: 51 tables refuse `DELETE` by trigger
   and not `TRUNCATE`, in ledger (4, `journal_entry` and `journal_line` among them), payments (11), fx (19), crossborder (8),
   merchant (6), checkout (1), kyc (1) and risk (1), so the owner of the journal can still empty it in one statement. **The
   clock**: 108 functions in accounting (18), bnpl (33), credit (16), risk (19), fx (8), payments (7), merchant (3), and
   checkout, crossborder, ledger and settlement (1 each) read a clock through a mutable `search_path`. Neither is reachable by a
   production role's own session today: `finapp_app` holds no `TRUNCATE` and no `CREATE` on any schema. Each was left because a
   fix needs a migration number in a module this task had none reserved in, and qualifying a schema's clock also needs that
   schema's clock-stepping test fixture (`BnplClockFrame`, the close's) re-opened as lending's was. The options: (a) one new
   Phase 15 item before the database pass, one forward migration per schema, the same way, with the guard's lists emptied as it
   goes; (b) `TRUNCATE` for the ledger alone now, the rest after the phases; (c) accept. *Recommendation*: **(a)**, because the
   journal is the financial record and the migrator exists now; the remaining clock calls could follow in the same item or a
   second one, and the guard already names every one of them. **Decided 2026-11-09**: (a), as `P15-TSK-040`, before the database
   pass, with the clock calls in the same item; T13's and T33's rows are `PLANNED` on it.
12. **The findings the image and dependency scans and the static analysis carry** *(added 2026-11-08 by the scan task's
    gate; **decided 2026-11-09**)*. `PHASE_GATES.md` §5 Phase 15 criterion 2 allows a remaining HIGH or CRITICAL
    finding only as an accepted risk in this register with an expiry, and §1.4 lets only the owner accept one. So the
    following were proposed, and the owner **accepted them on 2026-11-09**: the jobs are green only because `infra/trivy/accepted-findings.yaml` and
    `infra/semgrep/baseline.tsv` apply them, each with a reason and an expiry, and `SecurityScansAreWiredTest` holds this
    list and those files equal. An entry the owner withdraws is removed from its file and the job goes red. *(Read "are
    **proposed, not accepted**" and "An entry the owner refuses" until the owner's decision.)*

    **(a) Eighteen HIGH findings on the application image and its libraries, no CRITICAL, no secret, no misconfiguration**
    (Trivy 0.76.2, 2026-11-08). The migrator image carries the first ten, the same base packages at the same versions, and
    none of the eight: its six third-party jars have no finding of their own, so it adds nothing to this list:
   - `CVE-2026-113351` (libexpat1), until 2027-01-31
   - `CVE-2026-115506` (libexpat1), until 2027-01-31
   - `CVE-2026-117610` (libexpat1), until 2027-01-31
   - `CVE-2026-118370` (libexpat1), until 2027-01-31
   - `CVE-2026-76642` (libuuid1), until 2027-01-31
   - `CVE-2026-78408` (libuuid1), until 2027-01-31
   - `CVE-2026-78409` (libuuid1), until 2027-01-31
   - `CVE-2026-78410` (libuuid1), until 2027-01-31
   - `CVE-2026-76844` (zlib1g), until 2027-01-31
   - `CVE-2026-85091` (zlib1g), until 2027-01-31
   - `CVE-2026-68497` (jackson-databind 3.1.5), until 2026-12-31
   - `CVE-2026-91776` (jackson-databind 3.1.5), until 2026-12-31
   - `CVE-2026-91777` (jackson-databind 3.1.5), until 2026-12-31
   - `CVE-2026-89407` (jackson-core 3.1.5), until 2026-12-31
   - `CVE-2026-89425` (jackson-core 3.1.5), until 2026-12-31
   - `CVE-2026-106451` (lz4-java 1.10.1), until 2026-12-31
   - `CVE-2026-108106` (snappy-java 1.1.10.7), until 2026-12-31
   - `CVE-2026-93451` (snappy-java 1.1.10.7), until 2026-12-31

    The first ten are in the Debian 13 packages of the pinned distroless runtime base, whose digest is the current digest of
    its tag (checked 2026-11-08): `libexpat1` and `libuuid1` have no fixed Debian package, `zlib1g`'s fix is not in a
    distroless build yet. *Recommendation*: accept, until 2027-01-31, re-read each time the pin check reports a newer base.
    The last eight are the application's own libraries and each has a fixed release; moving them changes the JSON
    library and the Kafka codecs every module runs on and needs the full test tiers, so it was not done inside a scan
    task. *Recommendation*: accept, until 2026-12-31, and add **one small backlog item to Phase 15, before the database
    pass**, moving Jackson to 3.1.7 or later and `lz4-java` and `snappy-java` to their fixed releases, so that the exit's
    database pass runs on the moved versions; then these eight rows are removed.

    **(b) Fifty-six rows of `infra/semgrep/baseline.tsv`, each read and reasoned on 2026-11-08, until 2027-06-30.**
    Sixteen are T43's unbounded reads: each simulated provider's answer is read whole and then checked against the
    class's size bound, so a hostile answer is allocated before it is refused. They are real and small here because
    every provider is simulated (`PHASE_15_PLAN.md` section 1.1); a read bounded before it allocates belongs to the
    first real provider integration. Forty are statements the T45 rules cannot prove constant, although each is:
    a `StringBuilder` of literals, a field holding one of two private constants, a `Map` of constants read by an enum key, an enum's own name or SQL value list, a
    private helper handed only constants through a forwarding overload, a report-snapshot proxy forwarding its caller's
    text after screening it, the migrator's role bootstrap (DDL takes no bind parameter: an enum role name and a SCRAM
    verifier, or the server's own `format()` over a constant template). These are about two in a hundred of the tree's statements. *Recommendation*: accept the
    fifty-six until 2027-06-30; a statement that changes loses its row by construction, and the baseline's date and
    row count are held by `SecurityScansAreWiredTest`.

    **Decided 2026-11-09**: (a) and (b) accepted as recommended, with the dates above, and the one dependency item added
    as `P15-TSK-041`. (a)'s last eight expire on 2026-12-31, so that item must land first.
18. **T10's and T37's residual: the tables the retention sweeps leave alone** *(added 2026-11-08 by the retention task's
   gate; numbered for the tasks that ran beside it)*. The audit archival is designed (`DATA_RETENTION.md` §3) and not
   built, so the audit table grows and no archive copy exists. Idempotency records and session rows are not swept: a sweep
   of the first waits on a uniqueness on the posting scope, the second on real volume to size it. The application role also
   keeps the `DELETE` on the idempotency record that nothing uses. **What already holds**: no audit record can be deleted by
   the application, no production statement deletes an idempotency record, and growth here costs storage and vacuum only.
   *Recommendation*: schedule all three, with the revoke of that unused grant, into Phase 16's partitioning and archival of
   high-volume tables, as the carried items' ruling re-dated them; decide nothing before then. **Decided 2026-11-09**: as
   recommended; T10's and T37's rows stay `GAP`s held by this item until Phase 16.

---

17. **T17: retrying an abandoned event at or past the dedupe window** *(added 2026-11-08)*. A publication whose
   acknowledgement was lost reached the broker and left no mark on the row, so an event abandoned after such an attempt may
   have been applied by a consumer whose dedupe record has since expired. Nothing the platform holds can prove otherwise.
   Built today: such a retry is refused unless every consumer of the type is naturally idempotent (no type is), so the event
   stays abandoned and blocks its aggregate until two people discard it. *Recommendation*: keep the refusal. The event is
   then a recorded gap, recovered from the owning domain's records, which is a loud and bounded failure, where a second
   financial effect would be neither. *The alternative*: let a retry past the window proceed on a second person's approval,
   the operator attesting from the broker's and the consumers' records that the first attempt was not delivered. It restores
   the event without a gap, at the price of a judgement the platform cannot check, and it needs a new four-eyes door. **Decided 2026-11-09**: keep the
   refusal.

27. **T13's residual: the instance clocks outside the five stores** *(added 2026-11-09)*. The stored-bounds rule reads every
   production source's SQL, and classifies each comparison of a timestamp column with a bind parameter; it cannot say whose
   instant a bind is, and it cannot see a bound judged in Java. Read by hand, two of what it surrounds do judge a stored
   bound on an instance's clock. **Routing's version in force** (`findVersionInForce`, read with `Instant.now(clock)` at the
   payment confirmation and the withdrawal): a version's start is floored on the database's clock, but this read is the
   instance's, so within the skew of a scheduled start a payment can be routed by the outgoing or the incoming version. **The
   dispute door** (`DisputeActs.requireRespondable`, from the response and the evidence upload), in Java, so outside the
   census: a stored `respond_by` judged on the instance's clock, so within the skew of the deadline a response can be
   refused or admitted early or late. The census also names instance clocks that place a cutoff or a window and decide
   no bound: a sweep's `UNKNOWN` arm and settlement's parse and acceptance backoff (the cutoff picks candidates or paces a
   retry, and a conditional write re-decides), a dispute-deadline gauge's horizon, and risk's rolling look-backs (a skewed
   instance counts a window shifted by its skew). *Recommendation*: leave the candidate, pacing and gauge cutoffs where they
   are; accept the look-backs as a count and not a bound; and move routing's read and the dispute door to the database's
   clock with their next change, before production use (one read each, as the card fee version's was). Routing selects a
   rail and prices nothing, and the door's window is the skew against a deadline the network set in days. *The alternative*:
   schedule the two as their own task now.

23. **T7's, T9's, T10's and T32's residuals: what a plan of the data tier cannot settle** *(added 2026-11-09 by the data-tier
   modules' gate; numbered for the tasks that ran beside it)*. Three questions, none yet asked of a real account.

   **(a) Where a backup under a separate key lives.** The plan's phrase, automated backups under a separate key, is not
   something RDS offers: its backups and snapshots carry the instance's key, and within one account and region AWS Backup's
   vault key does not change that. So a separate key means a copy that crosses a boundary, and each root requires that
   copy's destination. The options: a dedicated backup account in the same region, a copy under that account's key at a daily
   recovery point; replicating automated backups to another region, which alone keeps point-in-time recovery under another
   key and is Phase 16's multi-region work; or accepting that the backups share the volume's key. *Recommendation*: the
   dedicated backup account. It answers the threat that matters most, an attacker or a mistake inside the production account
   reaching the backups along with the database, and takes nothing from Phase 16. Add the cross-region replication there if
   a recovery point under the separate key tighter than a day is required.

   **(b) The audit archive's lock.** Its period is `OPEN` until a real jurisdiction is chosen, and its mode is the same
   kind of choice, so both are required inputs with no default. Neither root can be planned for real until someone chooses.
   *Recommendation*: when a jurisdiction is named, S3's `COMPLIANCE` mode in production, because it is the only mode nobody in
   the account can shorten, which is what "write-once" has to mean against an insider, and `GOVERNANCE` in staging, where a
   wrong period must stay correctable. A period set in the stronger mode cannot be undone for an object once written, so
   decide it before Phase 16 writes the first one.

   **(c) The path that applies the ACLs.** They are declared as resources, so a plan shows them, and applying them needs
   what no apply here has needed: a runner inside the network holding the provisioner's credential, and three applies in
   order, the middle one with ACLs not yet enforced, because MSK has no super user who could write the first one.
   *Recommendation*: run it from the same pipeline role item 7 settled, from inside the cluster's network, and finish the
   three stages in one sitting.

## 7. Decided against

As `PHASE_15_PLAN.md` §9.3 decided, and kept here so that the model does not count these as missing controls:

- **mTLS inside the cluster, and a service mesh.** With one deployable there is no service-to-service hop. The hops
  that leave a pod (B4, B5, B6) each have, or are given by a Phase 15 task, their own TLS and authentication. Today
  B4, B5 and B6 each require verified TLS off loopback, and B5 a SCRAM identity as well (B5 since 2026-11-08, when
  the Kafka clients' authenticated TLS was built).
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
