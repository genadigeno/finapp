# ADR-0144 — The financial-operation availability matrix: derived from where the record lives, held to the served routes, walked by the experiments

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Platform · every module that serves a route
Rests on: ADR-0005, ADR-0014, ADR-0016, ADR-0124, ADR-0127, ADR-0128, ADR-0129, ADR-0137, ADR-0139; `PHASE_16_PLAN.md` §10;
`THREAT_MODEL.md` §6 items 43 (g), 61 and 66; `docs/architecture/AVAILABILITY.md`; `RESILIENCE_EXPERIMENTS.md` E05, E06, E09, E72,
E73, E74; `OPERATIONS_RUNBOOK.md` §11.59.

## Context

`PHASE_16_PLAN.md` §10 asks for three things: every single point of failure registered with its answer (§10.1), the availability
targets (§10.2), and - because a service can be up while an operation must be refused - a matrix of financial-operation availability
per operation and per failure, checked by the experiments (§10.3). §10.3 gave six example rows and left an open question: should any
flow refuse automatically on a correctness signal, such as a non-zero trial balance?

Three facts shape the answer:

1. **Every operation's record is in PostgreSQL**, written in the command's own transaction with its idempotency record and its outbox
   row. Kafka is transport (ADR-0005; `NoDirectBrokerPublicationRulesTest`: only the outbox relay publishes), the projections are
   PostgreSQL's own tables, and no cache exists. Readiness includes the database (ADR-0016), so a lost database takes every replica out
   of the edge's endpoints.
2. **Which operations exist is already derived.** `EdgeAllowListTest` renders both edges' route lists from the handler mapping, so a
   list of operations held against those files cannot miss a door.
3. **The experiments already read the edge's answers.** The database failover drill's experiments read every answer of the public edge,
   with its method, path and both statuses (`experiments_edge_read`), to show no money-moving success while the database was away. The
   same reading can compare every operation the workload sends with a written verdict.

## Decision

1. **Four verdicts, one rule.** Each operation, under each failure, is `REFUSED` (an error and no record), `QUEUED` (answered with its
   record; what it publishes or causes waits durably and completes once), `DEGRADED` (served with a stated limitation: shed or refused
   openly before any effect, an `UNKNOWN` resolved by inquiry, a read that may lag, a fail-safe decision) or `AVAILABLE`. **No request is
   `AVAILABLE` or `QUEUED` while its record cannot be written, and none is `REFUSED` for the broker's sake.** A failure row has a kind;
   the database's experiments' rows are `record` (every request refused, schedules and events waiting) or `record-write` (every command
   and callback refused, no read served as normal), the brokers' are `transport` (no request refused). The kind follows from the
   experiments that check a row, not from its label.
2. **The operations are classes of the served routes.** `AVAILABILITY.md` §3 names nineteen operations by route patterns (methods and a
   path, `{name}` one segment, `/**` a prefix, the most specific match winning); every route either edge serves belongs to exactly one,
   and every pattern is the best match of at least one. Two operations have no route: the scheduled work and the events.
3. **Every failure row is checked by registered experiments or is designed-only.** The single points of failure of §10.1 are registered
   with their state on `kind` and in the reference, their answer and their check. Two gaps the register found are closed by experiments
   of their own: the telemetry back ends lost (E72, a single point for detection on `kind`) and the payout rail and card tokenisation
   lost (E73, executable here and taken away by no experiment before). The rails no simulator on `kind` serves are designed-only
   (`RESILIENCE_EXPERIMENTS.md` D05) and put to the owner (`THREAT_MODEL.md` §6 item 66 (b)).
4. **The experiments walk their rows.** `availability_check` in the experiments harness reads the matrix from the document itself, takes
   each edge answer in the fault's window to its operation, and compares: no success where `REFUSED`, a success where `QUEUED` or
   `AVAILABLE` once any answer was seen. A mismatch fails the experiment; a row claiming service whose window exercised nothing could not
   show itself and fails too; an operation the workload did not send is recorded as not exercised, never passed. E05 and E06 walk the
   database's loss (A01), E09 every broker's (A06), E72 and E73 their own (A14, A11). The guard holds that the walks the harness makes
   are exactly the ones the document declares. The entry's probe is run-time: the run's own copy claims transfers available while the
   database is away (`REHEARSAL_PROBE=matrix-mismatch`, with E06), and the walk must fail.
5. **No automatic refusal on a correctness signal - proposed, for the owner** (item 66 (a)). A non-zero trial balance cannot come from
   a posting (every entry balances at its write, held by the database), so it means a fault outside the write path that a person must
   find; the signals are gauges, and an automatic freeze would turn their own failure (`CorrectnessUnverifiable`) into an outage; and
   stopping every customer's access to their money is a harm that needs a person's judgement. The freeze stays a person's recorded act
   (`OPERATIONS_RUNBOOK.md` §11.20, §11.12). A narrow automatic hold, scoped to one product or currency and written as the audited fact a
   person writes, is the option once the signals' false-alarm rates are measured - the stance owner decision C12 took for a rail's
   availability. The one automatic refusal that exists keeps its shape: a restored database refuses the platform's logins until its
   verification passes and a person releases it (ADR-0140).
6. **The availability objectives at the sustained profile are read, not judged here.** Every `load` run reads each availability
   objective of `SLOS.md` §2 over its window from the platform's own SLI rules, beside the target and the burn rate it ran at
   (`load/slo-availability.tsv`); at the sustained profile that reading is E74. A load run fails on correctness only, and validating or
   recalibrating the objectives is the SLO validation task's.

## Alternatives Considered

### The matrix as prose, checked by review
Readable, and a new route or a changed verdict would drift unseen. Rejected: the route lists are generated, so coverage can be derived.

### One row per route
Exact, and four hundred rows long under twenty failures. Rejected: routes share their mechanism within an operation, and the guard still
holds every route to one operation.

### A walk per operation, every operation driven during each fault
It would check every row operation by operation. It needs a client for every flow the platform serves, most of which the rehearsal
cannot drive (no simulator serves the instant scheme, the corridors, the KYC provider or a bureau). Rejected for now: the walk covers what
the workload sends, and the other operations rest on the same mechanism and on decision 1's rule, which the guard holds over every row.

### Refuse money movement automatically on any correctness page
Decision 5's reasons; not recommended.

## Consequences

- A new route fails the build until it is placed in an operation, and so has a verdict under every failure.
- A verdict that contradicts where the record lives fails the build; a verdict the platform contradicts fails the experiment that walks
  it, once its run is made.
- E05, E06 and E09 each gain a way to fail: a real mismatch. An unexercised operation is recorded, not failed.
- Nothing here has run on a cluster: E72, E73, E74, the walks and the probe are owed under `POSTPONED_ISSUES.md` PI-025.

## Invariants / Constraints

PostgreSQL is the only financial truth (`CLAUDE.md` rule 12). `INV-EVT-01`, `INV-EVT-02`, `INV-IDEM-*`. No new invariant.

## Follow-up

- The owner decides `THREAT_MODEL.md` §6 item 66: (a) automatic refusal on a correctness signal; (b) the rails no simulator serves.
- The ten-replica runs and E25's walk of row A09 are the instance-failure task's; validating the objectives E74 reads is the SLO
  validation task's.
