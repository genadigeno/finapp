# ADR-0145 — Capacity calibration: the autoscaler on CPU alone, a burst never scaled, the per-source limits kept by derivation, and no role split

Status: Accepted (2026-11-16, `P16-DOC-001` — read against the code and corrected first)
Date: 2026-11-13
Phase: 16
Context: Platform (the deployment, the edge, the public doors' limits) · every module (operated at scale)
Rests on: ADR-0001, ADR-0014, ADR-0016, ADR-0024, ADR-0120, ADR-0126, ADR-0134, ADR-0136, ADR-0137; `PHASE_15_PLAN.md` §2.2 C5;
`PHASE_16_PLAN.md` §4, §5.1 and §14; `CAPACITY_MODEL.md` §2, §3.1, §3.4, §4 and §4a; `THREAT_MODEL.md` §6 items 22 (b), 33 (b), 56 and 67.

## Context

Phase 15 shipped reference figures for everything that sizes the fleet and guards its doors: the autoscaler's 70% CPU target and its
windows, the pods' requests and limits (and so the namespace quotas), the public doors' per-source limits and the edge's per-source tiers.
Each said "calibrated in Phase 16". Phase 15's C5 also left one question to measurement: whether schedules and consumers should leave the
request-serving replicas for a worker role. The owner postponed every `kind` run of Phase 16 on 2026-11-13 (`POSTPONED_ISSUES.md`
PI-025), so this calibration has no run of its own. It has three inputs on master: the target's rates (W1–W8, `CAPACITY_MODEL.md` §3.1),
the per-operation costs measured at three replicas (§3.4: the isolation run and E30), and the code's own bounds (the identity lockout,
the grace, the pool). The decisions below are taken on those, and each names the run that would confirm or overturn it.

**The measurement that decides most of it.** The first limit measured is the single writer's CPU (E30's step 4), not the replicas'. At the
engineering workload the replicas used about 0.5 of their 6 cores, throttled in under 0.1% of their periods, with every role on every
replica and every objective met. The relay fell behind at the target's checkout peak with no resource near its limit, because its drain is
bounded per relay (ADR-0136). The census starved at the break because it shared the **pool**, which the shedding door answers (ADR-0137).

## Decision

1. **The autoscaler scales on the pods' CPU against their request, and on nothing else.** Latency, the pool's queue and the request rate
   rise when the writer is the limit, where more replicas add load and connections to it and spend §4's budget for nothing; a replica's CPU
   rises only when another replica helps. Memory is never a metric (a JVM's heap does not shrink). No custom-metrics adapter is installed.
2. **Its target stays 70% of the 500m request**, derived (`CAPACITY_MODEL.md` §4a.1): the replicas need about 1.37 cores at the target's
   peak (each operation's measured cost on the replicas times its peak rate), which is four replicas at 0.35 of a core each; W2's 60-s
   burst then runs each pod at about half its 2-core limit; the day's average leaves three pods at 38% of their request, the floor.
3. **A burst the standing fleet carries never scales it: the scale-up window is twice W2's burst (120 s, was 60 s)**, so the lowest count
   the autoscaler computed in its window is always one from outside a burst. A pod that arrives after the burst is two rebalances and a
   pool opened and closed for nothing; a real rise waits one more minute, which shedding covers. Held by `KubernetesManifestsTest`, which
   reads W2 from the capacity model.
4. **The scale-down window is at least one grace** (300 s kept; the manifest policy's floor raised from a minute to the grace), so a step is
   read only after the last one's pod has gone; one pod per period of at least the grace, as before.
5. **The pods' resources and the quotas stay.** The request is a pod's share of the peak at four replicas; the limit is the burst's
   headroom. Production's 2 GiB memory waits on E39's eight-hour soak: `kind`'s 1 GiB pods ran for 277 s, not hours.
6. **The per-source limits keep their numbers, now derived** (`CAPACITY_MODEL.md` §4a.2): each door at least one person's need (sign-in
   the lockout's ten failures and a success, read from `LockoutPolicy`; every other door an attempt and two retries), and sign-in at least a
   shared address of R6's 1,000 active customers in the busiest hour with W2's burst in the window and every sign-in retried once.
   `PerSourceLimitsAreCalibratedTest` holds both, every input read. **R6 is a new reading**, the owner's to change.
7. **The edge's per-source tiers keep their numbers, now derived** (§4a.3), each times the public edge's two controllers against its
   sources' need at W2's spike; held by the same test.
8. **The front door is designed only** (item 22 (b)): a per-source request rate of 120 a second and 130 requests in flight in front of
   the edge, the providers and merchants scoped by their own paths, sized from the edge's own tiers; it never relies on a provider's rule
   until an environment chooses one and its documentation is read. Several sources together can still fill the spool: item 22 (b)'s
   acceptance stands.
9. **No role split.** One Deployment, every replica every role (ADR-0120 decision 1 stands, its rejected alternative "two Deployments of the one image" still rejected). A split moves no work off the
   first limit (the background roles' statements reach the same writer), the relay's drain would fall with fewer relays, the census's
   starvation was a pool's and is answered by ADR-0137, and a split would cost two Deployments, two budgets in the formula, and every
   schedule's competitors cut to the worker count. **Reopened by an ADR** if E77 shows the background roles raising request latency on
   the replicas' own resources (their CPU throttled or their threads exhausted) while the database is below its limit, if E52 shows the
   monitors starved with the door in place, or if E69 and E70 show claim contention at ten harming requests.
10. **The autoscaler's own signals are decided and not built** (item 67 (b); *building them awaits the owner since 2026-11-13: corrected by `P16-DOC-001`, 2026-11-16*): a ticket once the count has stood at the ceiling for 30
    minutes, and the autoscaler's failed reads of its metric from the cluster watcher's event stream. The platform's rules read only
    series captured from the pinned images, and these never were; capturing them takes a cluster.

## Alternatives Considered

### Scale on request latency, the pool's waiters or the request rate (a custom-metrics adapter)
Pros: closer to what a customer feels. Cons: each rises when the single writer is saturated, where a new replica adds load and eight
connections to it; an adapter is one more privileged component and image. Rejected.

### A lower target (50–60%)
Pros: more standing headroom. Cons: the 2-core limit already carries W2's burst at half its size, and every extra replica is a pool, a
rebalance and more schedule competitors. Rejected.

### Keep the scale-up window at 60 s
Pros: a minute quicker on a real rise. Cons: a 60-s burst can end inside it and still add a pod that arrives after the burst and leaves
five minutes later: the oscillation the backlog names. Rejected.

### Lower or raise the per-source limits
Lower: refuses a shared address's legitimate sign-ins at the busiest hour, while one device can still spend the rest. Raise: gives every
single source more guesses. Neither answers the shared address's fate, which needs a different key (item 67 (a)). Rejected.

### An `api`/`worker` split now
Rejected for the reasons in decision 9; it remains the answer if E77 overturns this ADR.

## Consequences

- One change to the deployed shape: the scale-up window, 120 s, in every overlay. Everything else keeps its value and gains a derivation
  that a guard holds where one can (`KubernetesManifestsTest`, `PerSourceLimitsAreCalibratedTest`, the manifest policy's `[hpa-scale-down]`).
- A changed W2, W3 or R6 turns those guards red until the windows and the limits are derived again.
- The runs that confirm all of it are built and owed (PI-025): E75 (a burst never scales the fleet, no door refuses it), E76 (the entry's
  probe), E77 (the role split's reading); with E69, E70, E39 and E52, which other tasks built.
- Production's ceiling stays the owner's (item 56). *(Corrected by `P16-DOC-001`, the Phase 16 exit review, 2026-11-16: decided by the owner on 2026-11-13, item 56 (i): it stays seven until the ten-instance runs (E41, E26, E69, E70) and this calibration's confirming runs are made, all owed under PI-025, and the declared `max_connections` is raised first.)*

## Revisit

When E75–E77 run, or E39, E52, E69 or E70; when W2, W3 or R6 change; when a real environment chooses a front door.
