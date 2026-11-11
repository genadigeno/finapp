# ADR-0120 — The production deployment model: one image, one Deployment of N replicas, a migration Job, managed state, a segmented edge

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (deployment) · every module (operated, not changed) · Security (edge and network)
Rests on: ADR-0001, ADR-0011, ADR-0014, ADR-0016, ADR-0023, ADR-0024, ADR-0030, ADR-0031, ADR-0036; owner decisions C2, C3,
C4, C5 and C9 of `PHASE_15_PLAN.md` §2.2 (ratified at their recommended defaults by the owner, 2026-11-07; *read "recommended defaults awaiting ratification" until then*).

## Context

The platform has never been deployed. It is a modular monolith: `settings.gradle.kts` declares 23 modules and `app`, and
only `app` produces a runnable boot jar. Migrations are module-owned and forward-only, and the application never runs
them (ADR-0011). Every schedule is leaderless and arbitrated in PostgreSQL (ADR-0014, ADR-0024). The connection budget is a
startup guard (`ConnectionPoolSizingGuard`). Liveness is the process alone and readiness includes the database
(ADR-0016). The actuator shares the API's port and is unauthenticated (two debt rows). Redis runs in `compose.yaml`, but
no code uses it. Object storage is deferred (ADR-0036).

The owner's brief asks how "every microservice" is containerised and deployed. No microservice exists, and inventing
some to answer the brief would break ADR-0001.

## Decision

1. **Two deployable units.** The first is `finapp-app`, the `app` boot jar in a multi-stage, digest-pinned image whose
   runtime stage is distroless Java 21 `nonroot`. It runs as a Kubernetes Deployment of **N ≥ 3** replicas. Every
   replica serves HTTP and runs every schedule, consumer and relay, as today. The second is `finapp-migrator`, Flyway
   over every module's migrations with the build's settings. It runs as a Job once per release, before the rollout, as
   `finapp_migrator`.
2. **Rolling, never surging past the budget, terminating pods counted.** The rollout uses `maxUnavailable: 0` and
   `maxSurge: 1`. A PodDisruptionBudget sets `maxUnavailable: 1`. Topology spread covers zones and nodes. The HPA starts
   at three replicas and scales down by at most one pod per period no shorter than `terminationGracePeriodSeconds`.

   A Deployment's rollout and a ReplicaSet's replacement do not count terminating pods, and a terminating pod keeps its
   whole pool open until its grace ends (KEP-3973). So the budget rule counts those pods:

   ```
   maxReplicas + 2 × maxSurge + 1 ≤ finapp.database.instances
   minReadySeconds ≥ terminationGracePeriodSeconds
   ```

   - The surge pods are counted once in `2 × maxSurge`.
   - The rollout's terminating pods are counted again in `2 × maxSurge`. `minReadySeconds` stops them stacking.
   - The `+ 1` is one more terminating pod, from an eviction the PodDisruptionBudget allows or from an HPA step.

   A manifest policy checks the rule, and the `kind` rehearsal measures the peak of `pg_stat_activity` for
   `application_name = 'finapp'`. `podReplacementPolicy: TerminationComplete` would remove the second surge term, but
   it is alpha and not enabled on managed clusters; it is revisited when it is generally available. *(Corrected by the
   transition's review, 2026-11-07, its I-1: this read "`maxReplicas + maxSurge ≤ finapp.database.instances`".)*
   *(Dated note, 2026-11-09, `P15-TSK-012`: **measured on `kind`, on one machine, at three replicas; the record is `OPERATIONS_RUNBOOK.md` §11.28.** The rule held through a rollout, a
   pod kill and a rollback with an eviction mid-way, each replica's reporting pool opened by a report so that all eight of its connections count, and an operator was admitted with the fleet
   held at the budget itself. At three replicas the formula's six pods are also the `kind` overlay's `ResourceQuota`: the shipped rollout reached five at most, `minReadySeconds: 0` with a long `preStop` reaches six,
   and it is the quota, not the rollout, that stops a seventh. With the quota raised by one pod and a new pod evicted while the old ones drained, the fleet went past 48. So the quota's tie to the formula is
   what makes the budget hold, and a quota moved without the formula is what breaks it. `maxUnavailable: 1` fails no request while two replicas answer, so what it costs is the ready floor. No decision here changes.)*
3. **Probes map to ADR-0016.** The liveness, readiness and startup probes are served on a **management port**. No
   Service or ingress exposes that port. The image has no Docker `HEALTHCHECK`.
4. **Graceful shutdown is stated and tested.** The sequence is: a `preStop` pause, graceful HTTP, schedules drained
   within a bound, consumers closed, and the connection pool closed last. Correctness never depends on the drain: a
   killed pod's leases expire and its unpublished outbox rows are re-published.

   *(Application half built by `P15-TSK-008`, 2026-11-08; the `preStop` pause and the grace are `P15-TSK-009`'s
   manifest. Decision 3: the actuator answers on 8081 only, bound to loopback unless `FINAPP_MANAGEMENT_ADDRESS` names
   the pod's address. Decision 4, as Spring's own phases run it on a real context (`ShutdownOrderTest`): readiness turns
   `REFUSING_TRAFFIC` before any phase stops; the API port drains (`server.shutdown: graceful`, within
   `spring.lifecycle.timeout-per-shutdown-phase`, 30 s); no new schedule tick starts and the 32 (35 since `P15-TSK-029`, 2026-11-08) drain in parallel,
   each within 10 s and an interrupt's 2 s (*read "the 32 schedules stop claiming at once" until `P15-TSK-009`*); the consumers take no new batch, acknowledge the one they hold and leave
   their groups, within 10 s and 2 s; the management server closes, then the web server; the gauge refresher
   lets its read end, within 3 s (the accounting gauge reader until `P15-TSK-024` moved every gauge onto `platform`'s
   `GaugeRefresher`, its bounded stop carried over); the beans are destroyed, the pool after every stop has returned. Every component's
   stop bound is below the phase timeout, and the lifecycle's worst case is 30 + 12 + 12 + 3 = 57 s before the
   producer's close (`finapp.kafka.ack-timeout`, 10 s) and the pool's. The phases are `ShutdownOrder`'s. This note
   changes no decision; the ADR stays `Proposed` until the phase's exit review.)*
5. **State lives outside the cluster.** PostgreSQL and Kafka are managed services. In-cluster copies exist only in the
   test-only `kind` overlay, and a policy refuses them in any other overlay. Redis is not deployed. The application uses
   no object storage.
6. **The edge is segmented.** An ingress controller terminates TLS, so the application is never a TLS endpoint. A public
   ingress serves an allow-list of customer, merchant and provider-callback routes. An internal operator ingress serves
   every route that an operator permission guards. NetworkPolicy is default-deny. There is no API gateway product, no
   service mesh and no in-cluster mTLS.
   *(Dated note, 2026-11-09, `P15-TSK-011`: **the controller is Traefik v3, by digest, one controller per surface, on the file
   provider.** ingress-nginx, the name this decision's "an ingress controller" would have read as, was retired by its maintainers
   in March 2026 and takes no further fixes, so the component holding the internet boundary would stop being patched. The two
   "ingresses" are two controllers rather than two Kubernetes `Ingress` objects, because that API can state neither a method nor a
   template: `Prefix` is not `/v1/payments/{id}/capture`, and a path-only list has no answer for a path a customer `GET`s and an
   operator `POST`s. A route in the proxy's own rule language states the method and the whole path. The rules arrive in a hash-named
   ConfigMap, generated by `EdgeAllowListTest` from the handler mapping and the route permission register and held by it byte for
   byte. So neither controller holds a Kubernetes credential or reads a Secret through the API; the public one holds no operator route
   at all, and its `Host` header decides nothing; and the matching `infra/scripts/edge-check.sh` proves is the matching the same
   bytes do in the pod. No decision here changes: no gateway product, no mesh, authentication and authorisation in the application
   alone.)*
7. **No Kubernetes CronJob, leader election or sticky session carries anything authoritative.**
8. **Rollback is a digest revert. A schema is never rolled back.**

## Alternatives Considered

### Extract services to match the brief's wording
Pros: it matches "every microservice".
Cons: it costs the atomicity ADR-0001 keeps. Extraction belongs to Phase 16 and must be justified by measurement.

### Two Deployments of the one image (`api` and `worker`)
Pros: background load is isolated, and API pods scale independently.
Cons: it is an optimisation that needs Phase 16's measurement. It also needs a new liveness rule that no role ever runs
at zero. Correctness is already leaderless. The role flags are kept, so this stays possible later.

### In-cluster PostgreSQL and Kafka (operators or StatefulSets)
Pros: there is one control plane, and the stack is portable.
Cons: the journal is the financial record. A managed synchronous standby with point-in-time recovery is the lower-risk
bar. An operator is a credible Phase 16 option. A hand-written StatefulSet for the ledger's database is refused.

### An API gateway product; a service mesh
Cons: a gateway would be a second authorisation point that can disagree with the application. A mesh protects a
service-to-service hop that does not exist in a single deployable.

## Consequences

Positive: the deployment follows the architecture instead of reshaping it. Multi-instance correctness stays where it is
proven, in PostgreSQL.
Negative: every replica carries every role. Consumer groups rebalance on every scale event, which the HPA's scale-down
stabilisation limits.
Operational impact: a migration Job gates every rollout. The management port moves the probes.
Security impact: the operator surface is no longer on the public edge. The operational endpoints are off every
Service. A compromised pod can reach almost nothing.
Financial impact: none to the rules. The N − 1 / N coexistence must be proven by ADR-0122's compatibility checks.

## Invariants / Constraints

ADR-0014: N is never 1. ADR-0024: no single-instance mechanism. The connection budget holds at maximum scale plus
surge. `INV-HIST-01`: no rollback rewrites history. A schema is only ever moved forward.

## Follow-up

Phase 16 covers the role split, PgBouncer, an in-cluster database operator and multi-AZ/region topology, each by
measurement. `P15-TSK-003`, `-004`, `-008`…`-012` build this ADR. `P15-DOC-001` reads it against them.

*As built for decision 6's NetworkPolicy, `P15-TSK-010` (2026-11-09); no decision changes.* Default-deny in both directions in
`finapp`, and the flows of `PHASE_15_PLAN.md` §6 by label and port; the namespaces a root of their own, because an overlay's
`namespace:` renames every Namespace it renders. Three facts, each read from the source rather than assumed, narrow what the
decision's words can mean: a NetworkPolicy cannot refuse a pod's resident node, so "the kubelet and Prometheus alone" is really
"Prometheus's label, and whatever runs on that node"; a `LimitRange` item of `type: Container` is defaulted from its own `max` by
the API server, so it would admit the pod it exists to refuse, and the item is `type: Pod`; Kubernetes RBAC has no expiring binding,
so break-glass's time limit belongs to the identity layer. Stated and checked; nothing has enforced them on a cluster yet.

*As built for decisions 2 to 5's manifests, `P15-TSK-009` (2026-11-08); no decision changes.* `deploy/`, a Kustomize base and
the overlays `kind`, `staging`, `production`. The HorizontalPodAutoscaler alone owns the replica count: the Deployment
states no `replicas`, because a manifest that does resets the HPA at every apply, and a reset from seven to three is four
terminating pods at once, past the rule's `+ 1`; N ≥ 3 is `minReplicas`. The `preStop` is a `sleep` action (Kubernetes
1.30 or later), the image having no shell. **The grace**, `terminationGracePeriodSeconds: 115`: the 10 s pause, HTTP 30, schedules 12,
consumers 12, the management server 2 and its worker pool 5, the API server 2 and its worker pool 5, the gauge refresher 3, the producer 10, each pool's abort loop 10,
and 4 for the JVM's exit; `minReadySeconds` equals it. The lifecycle's 57 s above was low: a scrape in flight held the management
server's graceful close for that child context's own phase timeout, Spring's default 10 s, the property binding only the parent's
processor (measured; `P15-TSK-008`'s review had said 30 s). That server now closes at once (`ManagementServerClose`), and the 57
is 71 with Tomcat's two stops and their worker pools counted. **The readiness probe** times out at 7 s, outlasting the pool's 5 s `connection-timeout`, so a
lost database reads as the `503` it is. `infra/scripts/manifest-check.sh` renders every overlay and runs `kubeconform -strict`
and the policies, and each policy refuses a planted violation in the same run; the policies read `application.yaml` itself
for the pools. An empty or any-local `FINAPP_MANAGEMENT_ADDRESS`, and a management port that is not its own, refuse startup
(`ManagementAddressGuard`). Secrets and endpoints arrive from the secret store; the Job and the test-only copies carry the
restricted profile too.

*As built for decision 1's image, `P15-TSK-003` (2026-11-08); the decision is unchanged.* The two bases' one
definition is the root `Dockerfile`'s `FROM` lines (`tag@sha256`): Dependabot's docker ecosystem reads `FROM` lines and
does not resolve `ARG`, so a separate pins file would have left it inert. The jar is built by Gradle outside the image;
the builder stage only extracts it. Reproducibility is measured on bytes: two builds, each from source (every task re-run,
the image with no cache), must match on the jar's sha256, the image id and every layer digest. Measuring it found the
jar was not reproducible: Spring Boot copied the CycloneDX SBOM, with its random serial and its own instant, into
every boot jar, so the SBOM is now kept out of the jar (an SBOM per image is `P15-TSK-006`'s), and the extracted files
take the commit's time. The image runs as `65532:65532`, set explicitly; the JVM flags are `-XX:MaxRAMPercentage=75.0`
and `-XX:+ExitOnOutOfMemoryError`; the size budget is 300 MiB of flattened filesystem (260.3 MiB measured).
`README.md` §5f.

*As built for decision 1's migrator, `P15-TSK-004` (2026-11-08); the decision is unchanged, and so is ADR-0011: the application
never migrates and Flyway stays off its classpath.* The image is a stage of the same root `Dockerfile`, on one base stage the
application's stage also names, so each pin still has one definition. Its build gives it its own context root, `migrator/`,
because BuildKit's `<dockerfile>.dockerignore` would otherwise have replaced the application's allow-list. It runs a small
program over Flyway's API rather than the command line: distroless has no shell to loop over the schemas, and only a program
can say which failure a retry can help. `DATA_MIGRATIONS.md` §2.1 is the contract; §2.2 is the separate one-time bootstrap of the
deployed roles, which holds the master credential alone.

*As built for decision 6's limits, `P15-TSK-022` (2026-11-09): the decision is unchanged.* The edge's per-source limits stay a first, coarse layer, one bucket per controller. The limits that count are the application's: a conditional statement in PostgreSQL per source and fixed window (`WindowCounter`, platform `V014`), across every replica, on the five doors that need no session and no signature. It is general enough for a count per identity (`P15-TSK-042`), and that does not need a decision of its own: the table is one row per scope, key and window, the caller bounds its keys, and nothing about it is a deployment choice. The table is `UNLOGGED`, so a count's rows add nothing to the WAL the ledger shares, and a refused count is read-only; a failover empties it, at the cost of one extra window's budget, once per failover.

*As built for decision 6's surfaces, `P15-TSK-042` (2026-11-11): the decision is unchanged, and gains one header.* The two controllers separate the routes, and now the people too: each sets `X-Finapp-Surface` (`public` or `operator`) in its `edge-headers` middleware, replacing whatever a client sent, and the application refuses a role-holding identity on the public surface at every door that issues or strengthens a session, or changes what proves it. The header is believed only because `finapp-app-ingress` (decision 6's NetworkPolicy) admits no pod but the controllers to the application's port - with the policy's own exception, a host-network process on the pod's node, which on EKS is admitted on every port and could reach the operator routes directly anyway; behind the edge a request without a usable marker refuses staff, and only where nothing is deployed - no edge network named and the database on loopback - is the one port both surfaces. The database's address alone is the switch of every marked local default (ADR-0019, ADR-0020), and here it is not enough: a deployment reaching its database through a loopback sidecar would read as a laptop. The edge ships first, as a release of its own, because an application without the change ignores the header. The lockout needed no store of its own after all, the window counter above included: staff are refused on the public surface before the lockout is read or counted, so its arithmetic in `identity.authentication_failure` is untouched and survives a failover as before. No new decision: the surfaces were this one's, and who may authenticate is identity's (ADR-0030).
