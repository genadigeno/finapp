# ADR-0120 — The production deployment model: one image, one Deployment of N replicas, a migration Job, managed state, a segmented edge

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (deployment) · every module (operated, not changed) · Security (edge and network)
Rests on: ADR-0001, ADR-0011, ADR-0014, ADR-0016, ADR-0023, ADR-0024, ADR-0030, ADR-0031, ADR-0036; owner decisions C2, C3,
C4, C5 and C9 of `PHASE_15_PLAN.md` §2.2 (recommended defaults awaiting ratification).

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
3. **Probes map to ADR-0016.** The liveness, readiness and startup probes are served on a **management port**. No
   Service or ingress exposes that port. The image has no Docker `HEALTHCHECK`.
4. **Graceful shutdown is stated and tested.** The sequence is: a `preStop` pause, graceful HTTP, schedules drained
   within a bound, consumers closed, and the connection pool closed last. Correctness never depends on the drain: a
   killed pod's leases expire and its unpublished outbox rows are re-published.
5. **State lives outside the cluster.** PostgreSQL and Kafka are managed services. In-cluster copies exist only in the
   test-only `kind` overlay, and a policy refuses them in any other overlay. Redis is not deployed. The application uses
   no object storage.
6. **The edge is segmented.** An ingress controller terminates TLS, so the application is never a TLS endpoint. A public
   ingress serves an allow-list of customer, merchant and provider-callback routes. An internal operator ingress serves
   every route that an operator permission guards. NetworkPolicy is default-deny. There is no API gateway product, no
   service mesh and no in-cluster mTLS.
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
