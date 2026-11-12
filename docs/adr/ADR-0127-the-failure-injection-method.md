# ADR-0127 — The failure-injection method: scripted experiments on `kind`, registered with their stop conditions, never against anything the run did not create

Status: Proposed
Date: 2026-11-12
Phase: 16
Context: Platform · every module (operated under failure)
Rests on: ADR-0016, ADR-0025, ADR-0026, ADR-0120, ADR-0124, ADR-0126; owner decisions C1, C2, C4, C6 and C7 of `PHASE_16_PLAN.md`
§2.2 (proposed, awaiting the owner); `PHASE_GATES.md` §5 Phase 16's second criterion.

## Context

The only failures the platform has been put through are the rehearsal's own steps: a pod kill and a rolling update at three
replicas, which ran on 2026-11-09 (`OPERATIONS_RUNBOOK.md` §11.28); and a database restart and a broker outage, which are
written and have never run (`POSTPONED_ISSUES.md` PI-024). No fault injector exists. The `kind` cluster has one node, and its
PostgreSQL and Kafka are single test-only instances. A failover and a broker loss therefore cannot be shown as things stand.
The owner's brief requires controlled, repeatable experiments with a hypothesis, safety boundaries, success criteria, a
termination condition, a recorded result and a remediation, and it forbids destructive experiments against real financial
systems.

## Decision

1. **Every experiment is a scripted step**, in a new `experiments` scope of the `kind` harness. Each step runs in this order: a
   steady-state check, the fault injected under a running workload, the observations, the stop condition, the recovery, and the
   financial census.
2. **The register is the source of truth.** Each experiment is a row of `docs/architecture/RESILIENCE_EXPERIMENTS.md` with every
   field the brief lists. A guard test refuses a row missing any of them. A filled result must name its date, its commit and its
   artefacts. A failed result must name a live backlog task as its remediation.
3. **Primitives first, plus one proxy.** Faults are injected with `kubectl` and `docker` primitives: deleting or evicting a pod,
   stopping or pausing a `kind` node container, scaling to zero, and a NetworkPolicy for a partition. Latency, loss, bandwidth
   and connection resets come from Toxiproxy, pinned by digest, placed only on the hops from the application to PostgreSQL, to
   Kafka and to the provider simulator. Nothing privileged is installed cluster-wide.
4. **The topology the experiments need, test-only.** The experiments get a multi-node `kind` cluster: one control plane and
   three workers, each worker labelled as a zone. They also get a PostgreSQL primary with a synchronous standby (C7), promoted by
   a person-equivalent script step that moves the Service, and three Kafka brokers with replication 3 and
   `min.insync.replicas` 2. All of it lives only in the `kind` overlay, and none of it reaches a deployed overlay.
5. **The safety boundary is enforced by the harness.** The harness refuses any Kubernetes context except the cluster it created
   and marked. It refuses any credential it did not generate and any provider endpoint other than the simulator. Every fault has
   a stop condition. The harness removes the fault on the first trigger, and also on its own exit, whatever the outcome.
6. **A correctness signal ends the experiment as failed.** A census clause or a correctness page during or after the fault is a
   failure of the experiment, whatever availability did.
7. **Designed-only experiments are written, never claimed.** The managed failovers, an availability zone's loss, a region's
   loss and a real provider's outage are written in the register as designed-only (C4), each with its local analogue named.

## Alternatives Considered

### Chaos Mesh
Pros: a catalogue of faults behind CRDs.
Cons: it needs a privileged DaemonSet and cluster-wide CRDs to do on `kind` what the primitives already do, and it adds a
controller to the supply chain.

### LitmusChaos
Same trade-off as Chaos Mesh, with a heavier control plane.

### `tc netem` inside the node containers
Pros: no new image.
Cons: it shapes every flow on a node, not one hop. A proxy names exactly the dependency it degrades.

## Consequences

Positive: each experiment is a reviewable script whose result cannot be recorded without its criteria. The supply chain grows
by one image.
Negative: the multi-node cluster and the data-tier pairs make the harness heavier. One machine bounds what a node or a zone can
mean.
Operational impact: experiments run like rehearsals, with artefacts and a record.
Security impact: the harness's refusals are the boundary. No real system is reachable from it.
Financial impact: none. Every experiment ends in the census.

## Invariants / Constraints

No experiment runs against a cluster, credential or endpoint the run did not create. Every experiment ends in the financial
census.

## Follow-up

`P16-TSK-001` writes the register and its guard. `P16-TSK-019` builds the toolkit. `P16-TSK-020`…`-023` and `P16-TSK-027` run the
experiments.
