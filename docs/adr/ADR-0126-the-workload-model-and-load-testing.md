# ADR-0126 — The workload model and load testing: three named workloads, k6 outside the build, invariants read during load

Status: Proposed
Date: 2026-11-12
Phase: 16
Context: Platform · every module (operated under load)
Rests on: ADR-0001, ADR-0014, ADR-0025, ADR-0026, ADR-0120, ADR-0124; owner decisions C1, C2, C5 and C8 of `PHASE_16_PLAN.md`
§2.2 (proposed, awaiting the owner); `DELIVERY_PLAN.md` §Phase 16 §11; `PHASE_GATES.md` §5 Phase 16's first criterion.

## Context

No HTTP load tool exists. The repository has two load-shaped things: in-process storms (`BnplStormDatabaseTest`,
`LendingStormDatabaseTest`, `SettlementReconciliationStormDatabaseTest` and the others), which run several application contexts
in one JVM against one Testcontainers database, each ending in a financial census; and the `kind` rehearsal's JDK driver
(`infra/scripts/kind-rehearsal/Rehearsal.java`), which sends the smoke flows and a rollout's workload through the real edge
and counts failures. Neither measures latency percentiles at a controlled arrival rate, and neither can tell what a production
fleet would carry. The project has no cloud account: every measurement happens on one development machine's `kind`. A capacity
figure reported without saying which workload produced it would therefore mislead in either direction.

## Decision

1. **Three named workloads, never confused.** The *demonstration* workload is a small fixed mix, chosen to fit the machine,
   that shows the invariants holding during load. The *engineering test* workload is the highest sustained mix the machine's
   `kind` carries within the latency objectives, found by stepping the arrival rate; it is measured, never chosen. The
   *proposed production target* is derived from stated assumptions (W1–W8 of `PHASE_16_PLAN.md` §4.2), each number traceable
   to one. Every result carries its workload's name and machine. A `kind` number is never presented as production capacity,
   and the target is never presented as measured.
2. **k6, pinned by digest, outside the build graph.** The load generator is a container image recorded in
   `infra/scanner-pins.sh` like Trivy, Semgrep and the other operational tools. It runs on the `kind` network against the real
   edge, as an outside client, using open-model arrival-rate executors, so that queueing in the client is counted rather than
   hidden. Its summary and time series are written to the run's artefacts. It adds nothing to the application's lockfiles or
   to `gradle/verification-metadata.xml`. `Rehearsal.java` stays the flows' setup: customers, sessions, signed callbacks.
3. **Invariants are read during load, not only after it.** Every 30 seconds the harness reads the correctness signals of
   `SLOS.md` §3 and the census clauses that read committed facts only, in a read-only repeatable-read snapshot. A correctness
   signal or a census clause failing at any sample fails the run, whatever latency did. After load comes the full census.
4. **Retries are part of every load.** A fixed share of commands is retried with the same `Idempotency-Key`, before and after
   the first answer. The census counts one effect per key.
5. **Bottlenecks are named before anything is changed.** A stress run ends with its first limit, read in a fixed order:
   generator, edge, application CPU, threads, pool, database CPU, locks, I/O, broker. A limit that comes from the architecture
   becomes an ADR before any change.
6. **The in-process storms stay the database-contention tier.** They are owed with the other own-container suites
   (`POSTPONED_ISSUES.md` PI-004, PI-023). Phase 16 writes no new one (C1).

## Alternatives Considered

### Gatling's Java DSL through its Gradle plugin
Pros: the platform's language; a mature open-model engine.
Cons: it brings a Scala and Netty dependency tree into the verified build graph, for a tool the application never runs.

### Grow the rehearsal's JDK driver into a load tool
Pros: no new tool.
Cons: correct arrival-rate scheduling, coordinated-omission correction and percentile recording would all be written from
scratch and then have to be trusted.

### Load from inside the JVM (the storms)
Pros: already counted by a census.
Cons: they bypass the edge, the network and the deployment, which is exactly what Phase 16 has to load.

## Consequences

Positive: every capacity claim names its workload and its machine. The invariants are judged while the platform is under
stress. The build graph does not grow.
Negative: the scenarios are written in k6's JavaScript, a second scripting language beside shell and SQL. One machine bounds
what can be measured.
Operational impact: a load run is a `kind` scope with artefacts, like the rehearsals.
Security impact: the generator holds only the run's generated credentials and reaches only the run's cluster.
Financial impact: none. The census judges the run.

## Invariants / Constraints

`INV-ACC-01`, `INV-LED-*`, `INV-IDEM-*` and `INV-EVT-*` are read during load. A run whose census or correctness signal fails is
a failed run, never a slow pass.

## Follow-up

`P16-TSK-001` writes the capacity model. `P16-TSK-006` builds the harness. `P16-TSK-007` measures the baseline. `P16-TSK-014` and
`P16-TST-001` run it at ten replicas.
