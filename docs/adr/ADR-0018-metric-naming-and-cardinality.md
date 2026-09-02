# ADR-0018 — Metric names are a contract, and no tag value may come from a request

Status: Proposed

Date: 2026-09-02

## Context

`P0-TSK-029` exists for a reason its own backlog entry states: *metric naming decided late becomes
inconsistent across contexts*. Twenty-four modules each inventing names gives an operator
twenty-four vocabularies and no dashboard that can be written once — and unlike code, a metric name
cannot be refactored. Every alert rule, saved query, runbook and dashboard written against it lives
outside this repository.

Two further questions arrive with the first metric, and both are cheaper to answer now.

**What may a tag contain?** Micrometer makes tagging effortless, and a tag carrying a request-scoped
value is two failures at once:

- **Cardinality.** Every distinct combination is a separate time series, held in memory and
  retained. One tag carrying an account identifier turns one series into as many series as there
  are accounts, and the failure mode is the metrics backend falling over — taking the ability to
  observe the incident with it.
- **Disclosure.** A metric is scraped continuously and retained for months by a system with
  different access control from the database. An identifier in a tag outlives the request that
  produced it by a long way, which is worse than the same value in a log that at least rotates
  (`INV-AUD-02`).

**And what does a metric report when it cannot be read?** The default answer — zero — is a lie an
operator acts on.

## Decision

**1. `finapp.<module>.<noun>[.<noun>]`, enforced by the build.**

Lower case, dot separated, at least two segments after the prefix so the owning module is always
named. Dots because Micrometer translates them to each backend's idiom (`finapp_outbox_pending` in
Prometheus); writing the Prometheus form would bake one backend into the name.

The `finapp.` prefix separates what this platform *promised* from what a library happens to expose.
A scrape carries JVM, HTTP, pool and framework meters alongside ours, and only ours is a contract.

`MetricConventionTest` checks this against the **live registry** rather than a list, so a meter
registered by a module that does not exist yet is covered without anyone remembering.

**2. A tag value must come from a small, closed set fixed at compile time.**

Never an identifier, correlation id, account, customer, amount, key, or path. The permitted keys
are an enumerated set in `MetricNames`; adding one is an edit a reviewer sees, which is the point.

**Correlation deliberately does not belong on a metric.** It belongs on a trace and in a log, both
of which are per-event and searchable. A metric answers *how many, how long, how often* — never
*which one*. `CorrelationSinkCoverageTest` records `metrics` as the one platform concern where
correlation must be kept **out**.

**3. Outbox depth and age are gauges over the database, not counters from the relay.**

ADR-0005 names them first, and this is the half measurable *without a relay running* — which is
the point rather than a convenience. A relay-side counter reports nothing when the relay is down,
which is precisely the incident an operator needs to see: a backlog growing because nothing drains
it looks, to a relay-side counter, exactly like a quiet afternoon.

Depth and age come from **one statement**, so the pair cannot describe two different instants — two
queries milliseconds apart can report zero pending beside a non-zero age, a state that never
existed and that an operator would reasonably spend an hour explaining.

**4. An unreadable metric is absent, never zero.**

The gauges report `NaN`, which Prometheus records as no data. A zero would say "the outbox is empty
and all is well" at the exact moment nothing can be known, and an alert written on `== 0` would
stay silent through the outage. Absent data is alertable; a comforting zero is not.

**5. `/actuator/prometheus` is exposed, and that widens an unauthenticated surface.**

`P0-TSK-027` deliberately allow-listed two endpoints. This is a third, and it publishes JVM
internals, HTTP route templates and pool statistics — a description of the running system. Two
things make it acceptable, and both are conditions rather than opinions: the **content** is
controlled, because no tag may carry a request-influenced value and the build fails on one; and the
exposure is **recorded as debt owned by `P0-EPIC-10`**, alongside health and info.

**6. The dashboard is a file in git, provisioned into Grafana.**

`allowUiUpdates: false`. A dashboard edited in the UI and never exported is lost the first time the
volume is discarded, and nobody can review a change nobody can see.

## Alternatives Considered

### A. Prometheus-style names in the code (`finapp_outbox_pending`)
Pros: What an operator types is what a developer wrote.
Cons: Bakes one backend into the source. Micrometer's translation exists precisely so the same
meter renders idiomatically wherever it is exported.

### B. Tag metrics with the instance identifier
Pros: Per-instance breakdown without relying on the scraper.
Cons: The unbounded tag this ADR forbids — in a fleet that scales, every replica multiplies every
series. Prometheus already attaches the target it scraped; which instance a sample came from is the
scraper's question, not ours.

### C. Relay-side counters for the outbox
Pros: Throughput, failures and dead-letters, which gauges cannot give.
Cons: They report nothing when the relay is down. Also unavailable today: nothing schedules a
relay, so the meters would be structurally always zero — which reads as "nothing is failing"
rather than "nothing is running". Deferred with the broker adapter, not faked.

### D. Report zero when the backlog cannot be read
Pros: No `NaN` to explain; every panel always shows a number.
Cons: The number is wrong in the direction that silences alerts.

### E. Keep the scrape endpoint closed until `P0-EPIC-10`
Pros: No widening of an unauthenticated surface.
Cons: No dashboard, so the task's acceptance criterion — a dashboard rendering live data — cannot
be met, and the recorded metrics debt stays open through Phase 3, which is where the ledger lands.

## Consequences

Positive:
- One vocabulary, enforced, before there are twenty-four modules to reconcile.
- The outbox debt recorded at `P0-TSK-020` is paid for depth and age.
- A stalled outbox is now alertable rather than discoverable by reading logs.

Negative:
- A third unauthenticated endpoint until `P0-EPIC-10`.
- Gauges cost a database query per refresh interval; bounded by a cache, but not free.
- Relay throughput and inbox rates remain unmeasured until those components run.

Operational impact:
- `/actuator/prometheus`; `prom/prometheus` and `grafana/grafana` in `compose.yaml`, pinned and
  covered by `verifyInfrastructureVersions`.
- The dashboard is `infra/grafana/dashboards/finapp-platform.json`.

Security impact:
- The endpoint is a widened surface; the content is constrained by a build failure.
- No metric may carry correlation, identifiers or anything else request-scoped.

Financial impact:
- None directly. The outbox is transport (`INV-EVT-02`); these numbers describe publication lag and
  never money, and nothing financial may be decided from them.

## Invariants / Constraints

- `INV-AUD-02` — the reason for the tag rule.
- `INV-EVT-02` — outbox metrics describe transport, not truth.
- `INV-MON-01` — the no-floating-point rule caught this work twice. `OutboxBacklog` was **fixed**
  (the SQL now casts to `bigint`); `OutboxMetrics` took the first two entries in an exemption set
  that had been empty since `P0-TSK-008`, because Micrometer's `Gauge` takes a `ToDoubleFunction`
  and there is no integer gauge. A count of rows and an age in seconds provably cannot reach a
  monetary path.
- `P0-TSK-013` (time discipline) — the ambient-time rule rejected `System.nanoTime()` for the gauge
  cache, and the injected `Clock` was used instead. The cache is a cost optimisation, not a
  correctness mechanism, so a clock step delays a refresh and breaks nothing.

## Follow-up

- **Relay throughput, failure and dead-letter counters** with the broker adapter and a scheduled
  relay (Phase 3).
- **Inbox duplicate and contention rates** with the first live consumer (Phase 3).
- **Alert rules** — this task delivers the signals and the dashboard, not the alerting policy.
  Thresholds need a workload to calibrate against (Phase 15).
- **Authentication on the scrape endpoint** with `P0-EPIC-10`.
