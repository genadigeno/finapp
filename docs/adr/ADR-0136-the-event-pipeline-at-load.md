# ADR-0136 — The event pipeline at load: the relay drains while its batch is full, and three test-only brokers where the experiments run

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Platform (the outbox relay, the inbox) · the `kind` rehearsal
Rests on: ADR-0005, ADR-0014, ADR-0024, ADR-0126, ADR-0127; `PHASE_16_PLAN.md` §7 and §5.4; `CAPACITY_MODEL.md` §3.4 and §5.3; owner
decisions C2, C4 and C6 of `PHASE_16_PLAN.md` §2.2; `POSTPONED_ISSUES.md` PI-025.

## Context

**The relay fell behind with nothing saturated.** `P16-TSK-007`'s isolation run (`CAPACITY_MODEL.md` §3.4, three replicas, one
broker) drove checkouts alone at the target's peak: 44.4 events a second written, and the delay from write to publication climbed from
6 s to 59 s in four minutes, while the database used 0.46 of its core and the broker 0.13. `event_freshness` (published within 60 s)
would be breached within minutes of the target's checkout peak, and the target writes twice that (89.3 events a second at peak). Read
from the code, two things bound the drain, neither of them a resource:

1. Each instance runs one cycle, then waits `finapp.outbox.poll-interval` (`PT1S`) whatever the cycle found. A cycle drains at most
   eight aggregates (`OutboxRelay`'s default). An instance therefore publishes at most eight aggregates a second plus a cycle's own time,
   however large the backlog.
2. Every instance reads the same oldest heads (32: eight times the overscan of four). The lock decides who drains an aggregate, but an
   instance that locks one a moment after another has drained it finds nothing left and, until this decision, **counted it** against
   its batch. A late instance could spend its whole batch on aggregates already published, so more instances added little drain.

`CAPACITY_MODEL.md` §5.3 asks that a limit confirmed by measurement become an ADR before any change.

**The experiments need three brokers; the other scopes are built on one.** `PHASE_16_PLAN.md` §7 runs the pipeline's experiments on a
test-only KRaft cluster of three brokers with the reference posture (replication 3, `min.insync.replicas` 2). Every `kind` scope ran
one broker until now, and several scopes' own steps assume it (`platform.sh`'s outage scales the broker back to one; `rolls.sh`
reads pod 0 and its claim). With three brokers each advertises a name of its own, so a client reaches each broker directly after the
bootstrap (`P16-TSK-019`'s routed note): the experiments' proxy needs one hop per broker.

## Decision

1. **A drained candidate takes no slot.** An aggregate the relay locks and finds nothing to attempt on (no due, unabandoned row: another
   instance drained it after the candidates were read, or its head stopped being due) is skipped and does not count against the
   batch; the cycle goes on down its candidate list. `RelayPollResult.aggregatesDrained` counts aggregates where something was attempted.
2. **The schedule drains while the batch is full.** A cycle that took every slot of its batch and failed nothing (`batchFull`) is
   followed at once by another, on the same thread; anything else ends the tick and the instance waits its interval. Before each
   further cycle the tick asks whether its stop has begun or its hold come on (`ScheduleDrain`) and ends there. A failed attempt never
   loops: a broker in trouble keeps the interval, and each row keeps its own backoff (`RetryPolicy`).
3. **Nothing that makes ten instances safe changes.** The per-aggregate transaction-scoped advisory lock, the read of an aggregate's
   rows inside that lock, the stop at a failed, backing-off or abandoned row, the order of publication by `event_id`, the conditional
   mark (`WHERE published_at IS NULL`), the batch size, the overscan, the interval and the retry policy are as they were. At least once
   to the broker and once per record at the consumer (the inbox) are unchanged.
4. **Three brokers, test-only, where the experiments run.** The `kind` overlay's broker StatefulSet is made three-capable and stays
   committed at one replica: its start script gives a pod a node id and its own advertised names whenever the rendered configuration
   names no node. The harness runs three in the experiments scope, always, and in the load scope when asked
   (`REHEARSAL_KAFKA_BROKERS=3`), from the run's own rendered copy, and refuses three in any other scope. At three: the catalogue's
   posture whole (`infra/kafka/broker.properties` unchanged, the topics at `infra/kafka/topics.yaml`'s replication and
   `min.insync.replicas`); SASL_SSL with SCRAM-SHA-512 on every broker's client listener, as at one; KRaft's quorum and the brokers'
   replication over mutual TLS from the run's local CA, the certificate's subject the one super user beside loopback's `ANONYMOUS`; the
   harness's admin tools on that mutual-TLS listener. In the experiments scope each broker has a Toxiproxy hop of its own and a Service
   of its own, and the application's hostAliases send each broker's name to its own hop; the safety boundary reads all six proxies.
5. **The pipeline's runs are registered, built and contract-tested, and owed.** E08, E09, E22 and E23 (`PHASE_16_PLAN.md` §9.2) and the
   registered runs E47 (the pipeline at the target's checkout peak and after a burst: lag, drain, the relay's ceiling, skew), E48 (the
   fleet scaled 3 → 7 → 3 under load) and E49 (the run's day replayed through the audited door) are steps of the harness held by
   `PipelineRehearsalContractTest`; none has run (the owner's word of 2026-11-13, PI-025). Decisions 1 and 2 are measured by E47 at
   three and at seven replicas when it runs.

## Alternatives Considered

### A shorter poll interval, or a larger batch, by configuration
Pros: no code change. Cons: an interval short enough to keep up with the target's peak polls an idle outbox several times a second on
every instance, and neither setting stops a late instance spending its batch on aggregates already drained. Rejected as the first
change; it remains a lever after E47 measures the ceiling.

### Claiming candidates instead of reading the same heads (a claim column, or `FOR UPDATE SKIP LOCKED` on the heads)
Pros: instances would never meet on one aggregate. Cons: a claim column is a second arbiter beside the advisory lock and needs its own
lapse; row locks on heads reintroduce what ADR-0005's per-aggregate lock exists to avoid (two instances on one aggregate's rows). Not
needed while a refused or drained candidate costs one round trip.

### Three brokers in every scope
Pros: every rehearsal on the reference posture. Cons: the release, rolls, rotations, platform and delivery scopes' own steps assume
one broker and none can be run to prove a change now (PI-024, PI-025); three brokers cost 2 GiB more on the one-node cluster those
scopes use. Put to the owner as `THREAT_MODEL.md` §6 item 58 (a).

## Consequences

Positive: an instance publishes as fast as its cycles run while there is a full backlog, and more instances add drain instead of
contending for the same few heads; the experiments run on the reference posture with every broker behind the proxy.

Negative: under a standing backlog the relay's thread no longer pauses between cycles, so its database round trips and broker sends
follow the backlog's size rather than the interval (bounded by the batch, the candidates and the per-event acknowledgement timeout),
and it holds one of the writer pool's seven connections for as long as the backlog stands, where before it held it for a cycle in
every interval. The pool's wait and the HTTP path's latency at E47's burst are what say whether that costs anything.
Neither change is measured yet: the relay's ceiling at three and seven replicas, and whether more relays add drain, are E47's, owed
under PI-025; `OutboxRelayTest#aDrainedCandidateTakesNoSlot` is written and owed to PI-023's database pass. Only the experiments and the
pipeline's load runs use three brokers.

Follow-up: E47 measures the drain; if the ceiling still sits below the target's peak at seven replicas, the next lever is the batch
size or the per-event acknowledgement (a pipelined send keeping per-aggregate order), each by measurement.
