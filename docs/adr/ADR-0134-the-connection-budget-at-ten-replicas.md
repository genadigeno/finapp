# ADR-0134 — The connection budget at ten replicas: a larger server, no smaller pool, no transaction-mode pooler yet

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Platform · every module (operated at scale)
Rests on: ADR-0014, ADR-0120, ADR-0121, ADR-0126; owner decision C3 of `PHASE_16_PLAN.md` §2.2 (decided at its recommendation by the
owner, 2026-11-12); `DISTRIBUTED_EXECUTION.md` §4a; `CAPACITY_MODEL.md` §3.4 and §4; `PHASE_15_PLAN.md` §12's pooler question.

## Context

Every replica holds a fixed-size writer pool of 7 and a reporting pool of 1, and a rollout's surge pod and one terminating pod hold
theirs too, so the most instances that hold pools at once is `maxReplicas + 2 × maxSurge + 1`. That fleet must fit the server's
`max_connections` less 12 reserved (`ConnectionPoolSizingGuard`, the manifest policy). Ten replicas are 13 instances and 104
connections; production's server declares 100, so 88 usable. Owner decision C3: measure first; raise the test-only server on `kind`
to the formula; for staging and production choose by measurement between a larger server, a smaller pool and a transaction-mode
pooler, the last needing the server-side prepared statements question answered. Production's `maxReplicas` stays 7 until then.

**What was measured** (`CAPACITY_MODEL.md` §3.4, three replicas on the reference machine's `kind`, 2026-11-13). At the engineering
workload (the stress profile's third step, 0.39 of the target's peak as written) the fleet's 21 writer connections were 5.0 active
on average and 9 at most, the longest wait 119 ms and none pending, while the replicas used 0.52 of their 6 cores. At the fourth step
every connection was held and up to 313 requests waited, because the database's CPU was held back in every one of its periods: the
pools saturated only because the database did. Nothing was measured at seven or ten replicas: those runs are owed under
`POSTPONED_ISSUES.md` PI-025.

**What a pooler would meet, read from the code and the driver** (2026-11-13, not run):

1. Every advisory lock in the platform's statements is transaction-scoped (`pg_advisory_xact_lock`, its `_shared` form,
   `pg_try_advisory_xact_lock`) and every setting is `set_config(..., true)` or `SET TRANSACTION`/`SET CONSTRAINTS` inside the
   transaction. A transaction pooler preserves these. `TransactionScopedDatabaseStateTest` holds it from now on.
2. **Isolation is set per session by the driver.** PgJDBC 42.7.13's `Connection.setTransactionIsolation` sends
   `SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ...` as a statement of its own, outside any transaction (its
   `PgConnection`), and Spring's transaction templates call it for a non-default isolation, as Hikari does to reset a returned
   connection. Twelve production classes ask for `REPEATABLE READ` this way (read 2026-11-13: `GlSnapshots`, `CreditReadingSnapshot`,
   `ExposureCensusMetrics`, `FxProofMetrics`, `LendingCapitalMetrics`, `ReconciliationMetrics`, `ReconciliationReportController`,
   `ReconciliationReports` directly; `BnplTransactions`, `LendingTransactions`, `SpringRiskTransactions` and `ReconciliationBeans`
   through a template): the census, the proofs and the snapshot reads that must see one snapshot. Under transaction pooling that
   statement runs on whichever server connection it is given, the transaction after it may run on another at `READ COMMITTED`, and
   the characteristic stays on a server connection whose next client's writes then run at `REPEATABLE READ`. PgBouncer tracks only
   the parameters the server reports to the client, and `default_transaction_isolation` is not one (its documentation's
   `track_extra_parameters`, read 2026-11-13). `readOnly` is safe: the driver's default `readOnlyMode=transaction` begins
   `READ ONLY` transactions.
3. **Server-side prepared statements.** The driver names a statement on the server after its fifth execution on a connection
   (`prepareThreshold` 5, 256 statements cached per connection), so every hot statement is a named server statement under load.
   PgBouncer tracks protocol-level named prepared statements in transaction mode when `max_prepared_statements` is above zero (its
   documentation, read 2026-11-13; SQL-level `PREPARE` is forwarded untracked, and none exists here); the other way is
   `prepareThreshold=0`. Either is a measurement, not a reading.
4. **A new hop.** TLS terminated and re-originated (`verify-full` on both hops), the roles' SCRAM secrets held by the pooler, the
   budget moved to the pooler's server pool (the guard and the policy count the application's pools, which would be the wrong hop),
   and a pooler down is every replica unready.

## Decision

1. **`kind` (test-only): a larger server.** The overlay states the formula for ten exactly: `maxReplicas` 10,
   `FINAPP_DB_INSTANCES` 13, `FINAPP_DB_SERVER_MAX_CONNECTIONS` 116 (13 × 8 + 12) and the test-only server's `max_connections=116`, its
   quota sized for 13 application pods. The pools are unchanged. A rehearsal run holds its fleet at its own count in its own rendered
   copy, with the formula at that count (`fleet_patch`), so a run at three still measures the rule rather than a margin.
2. **Staging: unchanged** (4 replicas, 7 instances, 56 of 88). Nothing measured asks for more.
3. **Production: `maxReplicas` stays 7** (10 instances, 80 of 88). When ten are wanted, the way is decided: **a larger declared
   server**, `max_connections` 116 in the overlay's one literal, which the Terraform root reads into the parameter group
   (`db.m7g.2xlarge`, 32 GiB, holds 116 backends with room; the module admits 20 to 5000), applied and the instance rebooted before
   the application rolls (`OPERATIONS_RUNBOOK.md` §11.14's order). Raising the ceiling waits for the ten-instance runs (E41, and E26 of
   `P16-TSK-014`, owed under PI-025) and the HPA's calibration (`P16-TSK-015`): `THREAT_MODEL.md` §6 item 56 puts it to the owner.
   *Derived, not measured*: at the target's peak, about 2.4 times the engineering workload, the replicas would use about 1.25 cores,
   which the HPA's target of 70% of 500m a pod answers with about four replicas, so seven is not what the target meets first; the
   single writer's CPU is.
4. **No smaller pool.** Nine of 21 connections held at most would fit a pool of 5 (13 × 6 = 78 ≤ 88), but each replica's pool also
   carries its share of the 37 schedules and the consumers, and the run's own defect (the source-join census starved of the pool at
   the break, routed to `P16-TSK-016`) shows the correctness monitors already short of it there. Past the break more connections would
   only queue more work inside a CPU-bound database, so a larger pool is no answer either. 7 + 1 stays.
5. **No transaction-mode pooler now, and none in a deployed overlay without its own ADR.** Adopting one first needs: isolation set
   inside each transaction (`SET TRANSACTION ISOLATION LEVEL` as its first statement, or `BEGIN ISOLATION LEVEL`) and
   `Connection.setTransactionIsolation` refused by a guard; the prepared statements measured through the pooler; the budget's guard and
   policy re-pointed at the pooler's server pool; `verify-full` on both hops with the SCRAM secrets' holder decided; and an experiment
   in containers with the arbiters' database cases through the pooler (owed to PI-023's pass for the cases, C2).

## Alternatives Considered

### A smaller pool everywhere
Pros: ten replicas fit today's server with no infrastructure change.
Cons: cuts the per-replica headroom the schedules, consumers and correctness monitors share, before `P16-TSK-016`'s per-class bound
exists; measured at three replicas only.

### A transaction-mode pooler (PgBouncer)
Pros: many client connections over few server connections; the budget then counts the pooler.
Cons: the driver's session-scoped isolation (above) is a correctness defect under it until the code changes; a new component, hop,
credential holder and failure point; it does not relieve the measured first limit, the database's CPU.

### Raise production to ten now
Pros: the owner's "10+ instances" in production.
Cons: no run at ten has been made (PI-025), and nothing measured says the target needs more than seven.

## Consequences

Positive: `kind` admits ten replicas within an exact budget, for `P16-TSK-014`; the HPA has a metric on `kind`; the arbiters' locks
are held transaction-scoped by a guard.
Negative: production's ceiling stays seven until the owner decides item 56; a pooler stays unavailable until the isolation is moved.
Operational impact: a rehearsal at three is unchanged in what it measures; a load run at ten needs the reference machine's memory for
13 pods at a rollout's worst moment, which `P16-TSK-014`'s risk names.
Security impact: none new. metrics-server on `kind` is the checked render the delivery scope already installs, told not to verify kind's
kubelets, test-only.
Financial impact: none. Operator access during an incident (the reserved connections) is what the budget keeps.

## Invariants / Constraints

`instances × (writer + reporting) ≤ max_connections − reserved` on every overlay, `instances ≥ maxReplicas + 2 × maxSurge + 1`; every
advisory lock and setting transaction-scoped; no money moves differently.

## Follow-up

The owed runs (E41 at ten; E26); item 56; the HPA's targets from measurement (`P16-TSK-015`); the pooler only through the steps in
decision 5 and an ADR of its own.
