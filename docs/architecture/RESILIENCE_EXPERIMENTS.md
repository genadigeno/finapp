# Resilience Experiment Register

*Written 2026-11-12 by Phase 16's first task, from `PHASE_16_PLAN.md` §9.2 and §13 (ADR-0127, `Proposed`), on master
`6981c167`. This is the source of truth for every failure experiment of Phase 16 (ADR-0127 decision 2).
`ResilienceExperimentsAreRegisteredTest` holds it on every build. Every result and remediation slot below is empty: no
experiment has run. Nothing here is a claim about a real environment, and nothing here calls the platform ready for
production.*

An experiment is not a demonstration. It states before it runs what it expects, how it stays safe, what success is and when it
stops. After it runs, it records what happened, where the evidence is, and who fixes what failed. A row that lacks any of these
cannot be recorded here, and the guard refuses it.

---

## 1. How to read this register

### 1.1 The fields

Every experiment, in §3 and §4.2, carries every field:

| Field | Means |
|---|---|
| ID | `E01`…`E26` are `PHASE_16_PLAN.md` §9.2's experiments; `F01`…`F15` are the experiments of §13's fifteen scenarios, one each. Ids are never renumbered or reused. *(2026-11-13: `E27` onward are runs a later task registers beside §9.2's list so that their results take this register's grammar; `E27` is the load harness's demonstration run, which injects no fault.)* |
| Scenario | What is done to the platform |
| Hypothesis | What the platform is expected to do, stated before the run |
| Affected | The units and modules the fault reaches |
| Expected behaviour | What an observer should see while the fault is on |
| Safety | What this experiment adds to the boundary every experiment keeps (§2) |
| Success criteria | What must be counted for the experiment to pass. Every experiment also ends in the financial census |
| Stop | The experiment's own termination condition. The harness removes the fault at the first trigger. **Every experiment also stops, as failed, at a correctness page or a failing census clause** (ADR-0127 decision 6), whatever its own condition says |
| Instances | The replica count it runs at, never fewer than 3, today's minimum (`deploy/base/horizontalpodautoscaler.yaml`, `minReplicas`). The run records the count it actually ran at |
| Runs in | The backlog tasks that run it. While the result is empty, at least one of them is still live |
| Result | Empty (`—`) until a run fills it (§1.2) |
| Remediation | Empty (`—`) unless a result failed (§1.2) |

A field that does not apply reads `N/A: ` followed by its reason. A bare `N/A` is refused.

### 1.2 A result, and its remediation

A run appends its result to the Result cell, in this form, and nothing else (the commit and the path each in backticks):

```
PASSED on 2026-11-20 at 10 replicas, commit `1a2b3c4d`, artefacts `build/rehearsal/experiments/E01`
```

The verdict is `PASSED` or `FAILED`. Then come the run's date, the replica count it ran at, the commit it ran on (seven to forty
hex digits), and the path of its artefacts, relative to the repository root of the machine that ran it (the harness writes
them under `build/rehearsal` unless `REHEARSAL_ARTEFACTS` names another place). Several runs of one experiment are separated
by `; `, oldest first, and the last one is the experiment's **standing** result. The run's numbers, commands and census are in
its artefacts and in the record the running task writes. The register holds the verdict and where to find the evidence.

- A `FAILED` result names its remediation in the Remediation cell: the backlog task that fixes it. While the standing result
  is `FAILED`, at least one task named there is still live (`PLANNED`, `READY` or `IN_PROGRESS`).
- A failure caused by the machine (memory, disk) is still `FAILED`, recorded as an environment limit with its numbers
  (`PHASE_16_PLAN.md` §19.1). A flaky result is never retried into a pass.
- An experiment that does not apply has the result `N/A: ` and its reason.

A running task may sharpen a field by a dated note in the cell. It never loosens a success criterion or a stop condition
without a dated note saying why.

### 1.3 The seven checks

Every scenario experiment (§4.2), and every experiment whose success criteria name them, counts the seven checks of
`PHASE_16_PLAN.md` §13:

- **(a)** no unintended duplicate financial effect: one effect per idempotency key, one journal entry per business fact;
- **(b)** no unexplained money created or destroyed: the trial balance zero per currency, every product proof `HOLDS`;
- **(c)** no silent mutation of history: the journal up to the fault's start unchanged, compared by checksum before and after;
- **(d)** no permanent loss of financial evidence: every evidence row present after recovery;
- **(e)** safe recovery and replay: a re-run converges;
- **(f)** correct idempotency: a same-key retry answers the stored result;
- **(g)** reconciliation detects what is unresolved: a planted discrepancy the scenario leaves is raised as a break.

Exactly-once is not claimed from a framework's mode. What a pass shows is effectively-once **effects**, counted by the census.

---

## 2. The safety boundary, for every experiment

From `PHASE_16_PLAN.md` §1.1 and ADR-0127 decision 5. It holds for every row, and the Safety column adds only what is specific
to that experiment.

- An experiment runs only against a `kind` cluster or a container stack the run itself created and marked. The harness refuses
  any other Kubernetes context.
- It uses only credentials the run generated, throwaway and test-only, and only the provider simulators. It holds no real
  customer, no real money and no connection to a real provider.
- Every fault has a stop condition. The harness removes the fault at the first trigger and on its own exit, whatever the
  outcome.
- Nothing privileged is installed cluster-wide. The tools are `kubectl` and `docker` primitives, NetworkPolicies, and Toxiproxy
  pinned by digest on the hops from the application to PostgreSQL, to Kafka and to the provider simulator (owner decision C6).
- This register names no credential, endpoint or host.

---

## 3. The experiments

`PHASE_16_PLAN.md` §9.2, every field filled. Where the plan left a field `—`, this register states it, and a task that runs the
experiment may sharpen it (§1.2). *(2026-11-13: `E27`, the load harness's demonstration run, is registered after them; it is not one
of §9.2's experiments and injects no fault. Its result is its tenth run's, the first green: the nine before it were the harness's
development runs on the same machine, each stopped red on what it found, and `OPERATIONS_RUNBOOK.md` §11.43's record lists them;
the artefacts are kept under `build/` on that machine, not in the repository.)* *(2026-11-13: `E28`, `POSTPONED_ISSUES.md`
PI-024's poisoned-row step run under owner decision C11, is registered after `E27` the same way; it is not one of §9.2's experiments.
Its result is its second run's: the first passed every assertion and read `COULD NOT RUN` on the harness's own status, as
`OPERATIONS_RUNBOOK.md` §11.39's last record says.)*

| ID | Scenario | Hypothesis | Affected | Expected behaviour | Safety | Success criteria | Stop | Instances | Runs in | Result | Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|
| E01 | One application replica crashes (`kill -9` of the JVM) | The others serve; its claims lapse and are taken; in-flight commands retried by key take one effect | `finapp-app` | Its in-flight requests fail; the Service routes around it in one probe period; leases lapse and are claimed | One pod at a time | 0 duplicate effects; 0 lost outbox rows; failed requests no more than those in flight on it; census green *(2026-11-13, sharpened, not loosened: the failed requests counted at the edge by the replica each was meant for, every one the killed replica's - in flight on it, or sent to it before its endpoint left the edge's list in the one probe period the expected behaviour allows - and none another's, the killed replica's own answers read in the same lines as the control; a claim held in flight on it is placed there before the kill and seen taken over and completed once by another replica)* | A second replica unready while the killed one is replaced | 3 and 10 replicas: 3 for the toolkit's proof, 10 under the sustained profile | `P16-TSK-019`, `P16-TSK-020`, `P16-TST-002` | — | — |
| E02 | Several replicas fail at once (half the fleet) | The rest carry the load or shed it before any effect | `finapp-app` | Readiness and the PDB keep at least one; the HPA replaces; latency rises, and no money is misstated | At least one replica always ready | As E01; shed requests left no record | Fewer than one ready replica for 60 s | 10 replicas, half of them killed | `P16-TSK-020` | — | — |
| E03 | Container termination and pod eviction during load, and the per-instance caches lost with them | Graceful shutdown drains; an evicted pod's work completes elsewhere; nothing authoritative was in the lost caches | `finapp-app` | `preStop`, readiness out, HTTP drained, schedules and consumers stopped in order (`ShutdownOrderTest`) | Eviction through the API, the PDB respected | 0 failed requests across a graceful eviction; census green | A request failed on a graceful path | 10 replicas | `P16-TSK-020` | — | — |
| E04 | Node failure (a `kind` worker stopped) | Pods reschedule; the zone spread keeps replicas on the other workers | every in-cluster unit | Pods on the node lost; replacements scheduled; the edge's second controller serves | One node of three | Ready capacity restored within the HPA's and the scheduler's bound; census green | The control plane unreachable | 10 replicas over three workers | `P16-TSK-020` | — | — |
| E05 | Database primary failure (the standby promoted) | Synchronous standby: nothing acknowledged is lost; replicas go unready and are not restarted; retries by key take one effect | PostgreSQL, every replica | `OPERATIONS_RUNBOOK.md` §11.40's behaviour; new connections reach the new primary | The test-only pair (owner decision C7) | 0 committed rows lost (row counts and the census against the pre-failover snapshot); the interruption measured; census green | The standby not caught up when the promotion is due: the promotion refused | 3 replicas at least | `P16-TSK-022`, `P16-TSK-028`, `P16-TST-002` | — | — |
| E06 | Database restart, unclean, without failover (`POSTPONED_ISSUES.md` PI-024's step) | Every replica goes unready and none restarts; on return they are ready; the unlogged window counters come back empty; nothing committed is lost | PostgreSQL, every replica | Readiness down and back, as `OPERATIONS_RUNBOOK.md` §11.40's `kind` half; fresh abuse budgets after the restart | The test-only server restarted by the harness, its volume kept | No replica restarted; census green | The server not accepting connections 10 min after its restart | 3 replicas at least | `P16-TSK-022` | — | — |
| E07 | Database connection exhaustion: other clients hold the reserve and the pool's share | Pool waits bound requests; readiness reflects it; operator access survives | every replica | `ConnectionPoolStarved` fires; requests time out at the pool's 5 s; no partial effect | A test client holding connections, released at stop | 0 partial effects; an operator connection admitted | 10 min | 3 replicas at least | `P16-TSK-021` | — | — |
| E08 | Kafka: one broker of three killed | ISR 2 holds; producers and consumers continue | Kafka | Leader elections; no error reaches the outbox | The test-only three-broker cluster | 0 records lost; lag drains | Two brokers down | 3 and 7 replicas | `P16-TSK-013` | — | — |
| E09 | Kafka unavailable (all brokers) for 10 min | Rows wait in the outbox; money still moves; nothing is lost | Kafka, every consumer | `OutboxPublicationStalled` fires and `event_freshness` burns; readiness holds (Kafka is not in readiness) | The test-only brokers, stopped and restarted by the harness | After the return: the outbox drained, every row published once, every effect once; census green | 30 min | 3 and 7 replicas | `P16-TSK-013`, `P16-TST-002` | — | — |
| E10 | Redis failure | N/A: no Redis or other shared cache is used by any code (`PHASE_16_PLAN.md` §3) | N/A: no unit depends on a Redis | N/A: there is nothing to fail | N/A: nothing is injected | N/A: there is nothing to count | N/A: nothing runs | N/A: nothing runs at any replica count | N/A: no task runs it, recorded here instead | N/A: no Redis is used by the platform; the only caches are per instance and non-authoritative, lost with a pod in E03 | — |
| E11 | Object storage unavailable | The application uses none; the restore's archive and the Terraform state are infrastructure | infrastructure only | No application effect; a backup copy and a state write wait | A local object store (a container) stopped | No application error; the backup step fails loudly and retries | The store stopped for more than 15 min, or an application error | 3 replicas at least | `P16-TSK-021` | — | — |
| E12 | Network latency between the application and the database (Toxiproxy, +50, +200, +1000 ms) | Latency rises; timeouts bound it; no partial commit | every replica | Pool saturation at the higher steps; shedding, if built, refuses before any effect | Toxiproxy only on the test hop | 0 partial effects; latency explained | p99 above 30 s | 3 replicas at least | `P16-TSK-021` | — | — |
| E13 | Packet loss and resets between the application and the database (1%, then 10%) | Failed statements roll back whole; retries by key converge | every replica | Errors counted; no half transaction | Toxiproxy only on the test hop, removed at stop | 0 duplicate or partial effects | 10 min at each step | 3 replicas at least | `P16-TSK-021` | — | — |
| E14 | Network partition: one replica cut from the database (a NetworkPolicy) while the others serve | The cut replica goes unready; its leases lapse; a black-holed connection waits until the socket bound (`THREAT_MODEL.md` §6 item 42 (h)) | one replica | `OPERATIONS_RUNBOOK.md` §11.40's black-holed case, measured | One replica; the policy removed at stop | The others' claims proceed; the cut replica's threads freed within the decided bound | The partition held 15 min, or a second replica unready | 3 replicas at least, one of them cut | `P16-TSK-021`, `P16-TSK-022` | — | — |
| E15 | Partitions between the application and Kafka, and between the application and the provider simulator | The outbox waits; provider calls become `UNKNOWN` or `503`, swept later | every replica | As E09 for Kafka; as E17 for the providers | NetworkPolicies on the test hops only, removed at stop | As E09 and E17 | 30 min on the Kafka hop, 10 min on the provider hop | 3 replicas at least | `P16-TSK-021` | — | — |
| E16 | DNS failure (CoreDNS scaled to zero, or a resolver answering errors, for 2 min) | Established connections continue; new ones fail and recover; nothing is misstated | every replica | Readiness may fall on new connections; recovery on return | The run's own CoreDNS, restored by the harness at stop | Census green; recovery time recorded | 10 min | 3 replicas at least | `P16-TSK-021` | — | — |
| E17 | External payment provider outage (the simulator answers `503`, then times out) | Commands answer `503` or `UNKNOWN`; sweeps resolve them later; an ambiguous call is never resent | payments, checkout | `PaymentUnknownAged` within its bounds; the SLO counts the `503`s | The simulator only | 0 payments posted without a provider answer; every `UNKNOWN` resolved once on return | 30 min, or a payment posted without a provider answer | 3 replicas at least | `P16-TSK-018`, `P16-TSK-029` | — | — |
| E18 | FX provider outage | Quotes refused or bounded by staleness; no conversion on a stale rate (`INV-FX-*`) | fx, crossborder | Quotes refused past their staleness | The simulator only | 0 conversions on a stale rate | 30 min, or a conversion on a stale rate | 3 replicas at least | `P16-TSK-018` | — | — |
| E19 | KYC provider outage | Verifications wait; no customer is activated without a decision | kyc | Cases pending; retries bounded | The simulator only | 0 activations without evidence | 30 min, or an activation without a decision | 3 replicas at least | `P16-TSK-018` | — | — |
| E20 | Credit bureau outage | Decisions take the recorded fail-safe (`bureau-none`), never an approval on missing data | credit | Recorded fail-safe decisions | The simulator only | 0 approvals without the policy's data | 30 min, or an approval without the policy's data | 3 replicas at least | `P16-TSK-018` | — | — |
| E21 | Slow external dependency (the simulator at 2 times, then 10 times its normal latency) | Thread use is bounded; inquiries are bulkheaded; other routes are unaffected | payments, every adapter | `CallbackInquiryBulkhead` refusals counted; the other SLOs hold | The simulator only, its latency restored at stop | Unaffected routes within their objective | 30 min, or an unaffected route past its objective for 5 min | 3 replicas at least | `P16-TSK-018` | — | — |
| E22 | Message duplication (the same records sent again; a consumer group reset to an earlier offset) | The inbox dedupes every applied record | every consumer | Duplicates counted as deduplicated | Inside the dedupe window, on the run's own brokers | 0 second effects | A second effect counted | 3 and 7 replicas | `P16-TSK-013` | — | — |
| E23 | Message delay (records held 10 min, then released out of order across aggregates) | Per-aggregate order is kept; late records are applied once | every consumer | Lag rises, then drains | Inside the dedupe window; the held records released by the harness at stop | 0 order violations per aggregate; 0 second effects | 30 min, or an order violation or a second effect counted | 3 and 7 replicas | `P16-TSK-013` | — | — |
| E24 | Database disk pressure (the data volume filled to its threshold) | Writes fail cleanly; no partial transaction; readiness and alerts tell | PostgreSQL | Write errors; the platform refuses work; recovery when space returns | A size-limited volume on `kind` | 0 partial effects; census green after recovery | The volume full for 5 min | 3 replicas at least | `P16-TSK-021` | — | — |
| E25 | An edge controller lost under traffic (`THREAT_MODEL.md` §6 item 43 (f)) | The second controller serves | edge | Connections on the lost controller fail once; the rest are served | One controller of two | Failed requests no more than those on the lost controller | Both controllers down, or the lost one not replaced in 10 min | 10 replicas | `P16-TSK-020` | — | — |
| E26 | Rolling deploy and rollback during load at ten replicas (scenario 14) | N − 1 and N coexist; zero failed requests; one effect per key | `finapp-app`, the migrator | As the release scope, at ten | The run's own images, built in the run | As `OPERATIONS_RUNBOOK.md` §11.28's assertions, at ten | A failed request, a second effect, or the rollout not complete in 30 min | 10 replicas | `P16-TSK-014` | — | — |
| E27 | The demonstration workload (`CAPACITY_MODEL.md` §1) driven through the edge for 10 minutes by the load harness's open-model generator, no fault injected: the load harness's own run (`PHASE_GATES.md` §5 Phase 16 criterion 1, `OPERATIONS_RUNBOOK.md` §11.43) | Every correctness page stays silent and every committed-fact census clause holds at every 30 s sample; every money movement resolves by its key and is counted once; the full census holds after | the edge, `finapp-app`, PostgreSQL, Kafka, the provider simulator, settlement and reconciliation | Latency, errors and saturation rise with the load and are recorded; nothing pages | The run's own cluster and generated credentials; the generator handed only its fixture and the edge's CA, its CPU bounded | Every sample PASS, the samples covering the load with no gap over 45 s; no iteration dropped and every arrival rate within 2%; 0 movements unresolved and every retried key answered alike; every callback answered and a duplicate accepted; each operation's errors recorded by status beside the chance its objective explains them, never a verdict (`PHASE_16_PLAN.md` §5.3; the objectives under load are `P16-TSK-029`'s); every ready replica serving each money-moving route; the full census green | A sample failing or unable to read, which stops the load at once; the generator unable to hold its rate | 3 replicas | `P16-TSK-006`, `P16-TSK-007` | PASSED on 2026-11-13 at 3 replicas, commit `7231d298`, artefacts `build/rehearsal/experiments/E27` | — |
| E28 | A poisoned row at a sweep's head: a captured card attempt copied under a version-4 id back into `CAPTURE_DISPATCHED`, an hour old (`OPERATIONS_RUNBOOK.md` §11.39 (a); `POSTPONED_ISSUES.md` PI-024's poisoned-row step, run under owner decision C11) | The card sweep's listing skips and records the row and reaches everything behind it; the row is never judged; `PaymentSweepRowUnreadable` pages naming no identifier; no replica's tick fails whole | `finapp-app`, payments | The skip's WARN line on the replicas that read it; a `payments.sweep_row_failure` record for the row; no `Payment sweep failed` line; `PaymentSweepFailing` silent | The plant is a raw insert by the run's own throwaway superuser; the row and its record are removed by the harness as the plant's teardown, never by a procedure | `PaymentSweepRowUnreadable` fired and reached the page receiver within 600 s; the record holds at least one failure; the row still `CAPTURE_DISPATCHED`; no replica's tick failed whole; census green | The page not fired in 600 s, the row judged, or a tick failing whole | 3 replicas | `P16-TSK-017` | PASSED on 2026-11-13 at 3 replicas, commit `e9cdbb3a`, artefacts `build/rehearsal/experiments/E28` | — |

---

## 4. The fifteen scenarios

`PHASE_16_PLAN.md` §13, the phase's most important part. Each scenario is executed end to end by `P16-TSK-027` against the
`kind` deployment, with the seven checks (§1.3) counted. It is registered as its own experiment in §4.2, built on the
experiments of §3 it composes.

### 4.1 The scenarios, and what already proves part of each

Tiers, read from each test's own tags: `[db]` needs PostgreSQL, `[kafka]` needs Kafka, `[own-container]` starts its own
database. None of these has run fresh since Phase 15 began: they are owed to `POSTPONED_ISSUES.md` PI-023's pass.
`[slice]` (a Spring context) and `[hermetic]` need no database and run per task. A "multi-instance" test is threads or a
second context in one JVM on one database. A rehearsal contract test holds the harness's text, not a run. *(Two corrections to
the plan's labels, 2026-11-12: `BnplOutboxAtomicityDatabaseTest` starts its own container, so it is `[own-container]`, not
`[db]`; `HealthEndpointTest` is a Spring slice, `[slice]`. `PHASE_16_PLAN.md` §13 carries the erratum.)*

| # | Scenario | Already proven in part | Not proven | Experiments |
|---|---|---|---|---|
| 1 | A transfer is committed but the HTTP response is lost | `platform/src/test/java/com/finapp/platform/idempotency/IdempotencyFailureModeTest.java#responseLostAfterCommitIsReplayed` [db]; `app/src/test/java/com/finapp/app/transfers/TransferExecutionDatabaseTest.java#tenConcurrentIdenticalKeysProduceOneTransfer` [db] | No real response lost on a network; one JVM | F01 |
| 2 | A payment provider succeeds but the response is lost | `app/src/test/java/com/finapp/app/payments/PaymentAmbiguityDemonstrationDatabaseTest.java#receivedBeforeLostAtCapture` [db]; `app/src/test/java/com/finapp/app/payments/PaymentSweeperDatabaseTest.java#anAgedCaptureUnknownResolvesToCapturedOnce` [db] | Sweep timing at load; a real network loss | F02, E15 |
| 3 | A ledger posting succeeds but event publication fails | `app/src/test/java/com/finapp/app/bnpl/BnplOutboxAtomicityDatabaseTest.java#aCrashBetweenPostingAndPublicationLosesNothing` [own-container]; `platform/src/test/java/com/finapp/platform/outbox/OutboxRelayTest.java#brokerUnavailabilityRetriesAndLosesNothing` [db]; `platform/src/test/java/com/finapp/platform/outbox/KafkaOutboxDeliveryKafkaTest.java#aCrashBetweenAckAndMarkRedelivers` [kafka] | One broker, no load | F03, E09 |
| 4 | A consumer processes an event and crashes before acknowledgement | `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaInboxDeliveryKafkaTest.java#aCrashBeforeTheOffsetCommitRedelivers` [kafka]; `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#aCrashBeforeCommitIsRecoveredByRedelivery` [db] | A killed process, not a closed consumer | F04, E01 |
| 5 | The same event delivered to multiple processing attempts | `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#concurrentDuplicatesProduceOneEffect` [db]; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaInboxDeliveryKafkaTest.java#aRebalanceEffectsOncePerRecord` [kafka] | Separate processes; at load | F05, E22 |
| 6 | A service crashes during a repayment allocation | `lending/src/test/java/com/finapp/lending/RepaymentDatabaseTest.java#aCrashAfterThePostingLeavesNothing` [db]; `lending/src/test/java/com/finapp/lending/RepaymentDatabaseTest.java#theSameRepaymentTwiceRepaysOnce` [db]; `app/src/test/java/com/finapp/app/lending/LendingStormDatabaseTest.java#theStormLeavesEveryLoanProvable` [own-container] | A killed process | F06 |
| 7 | Two instances concurrently capture the same payment | `app/src/test/java/com/finapp/app/payments/PaymentCaptureDatabaseTest.java#tenConcurrentCapturesProduceOneEntry` [db]; `app/src/test/java/com/finapp/app/payments/PaymentSweeperDatabaseTest.java#aSweeperRacingTheWebhookProducesOneEffect` [db] | Separate pods | F07 |
| 8 | Two instances concurrently release the same hold | `app/src/test/java/com/finapp/app/payments/WithdrawalHoldDatabaseTest.java#tenReleasesOfOneHeldWithdrawalDispatchOnce` [db]; `app/src/test/java/com/finapp/app/domain/HoldDatabaseTest.java#releaseRestoresAvailabilityExactly` [db] | A ledger hold released concurrently (only sequentially tested); separate pods | F08 |
| 9 | A settlement file ingested while a previous ingestion is incomplete | `settlement/src/test/java/com/finapp/settlement/IntakeAtomicityAndRacesDatabaseTest.java#tenParsersOverTenConflictingFilesLeaveOneLiveBatch` [db] | A parse stuck across a real process death | F09 |
| 10 | A reconciliation process crashes halfway through a batch | `app/src/test/java/com/finapp/app/reconciliation/SettlementReconciliationStormDatabaseTest.java#theStormReconcilesTheReconciliation` [own-container] | A killed process; load | F10 |
| 11 | A database primary fails during financial processing | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#readinessReflectsTheDatabase` [slice]; `platform/src/test/java/com/finapp/platform/outbox/OutboxCrashRecoveryTest.java#aKilledInstanceDoesNotStrandItsAggregate` [db]; `app/src/test/java/com/finapp/app/architecture/PlatformRehearsalContractTest.java#aLostDatabaseDegradesReadinessNeverLiveness` [hermetic], the harness's text for the restart that never ran (PI-024) | No failover anywhere; `OPERATIONS_RUNBOOK.md` §11.40 is a procedure, which proves nothing; the `kind` restart never ran | F11, E05 |
| 12 | Kafka becomes temporarily unavailable | `platform/src/test/java/com/finapp/platform/outbox/OutboxRelayTest.java#brokerUnavailabilityRetriesAndLosesNothing` [db]; `app/src/test/java/com/finapp/app/architecture/PlatformRehearsalContractTest.java#theBrokerOutageKeepsTheFleetReadyAndPages` [hermetic], the harness's text for the outage that never ran (PI-024) | A real outage at load | F12, E09 |
| 13 | A provider returns a delayed success after an internal timeout | `app/src/test/java/com/finapp/app/payments/PaymentAmbiguityDemonstrationDatabaseTest.java#timeoutThenSuccessAtCapture` [db]; `app/src/test/java/com/finapp/app/payments/PaymentWebhookTransitionDatabaseTest.java#lateAndOutOfOrderReportsAreEvidenceOnly` [db] | Real delivery delays | F13 |
| 14 | A service redeployed while transactions are in progress | `app/src/test/java/com/finapp/app/lifecycle/ShutdownOrderTest.java#theRealOrder` [slice]; the kind release scope, green on 2026-11-09 at three replicas (the record in the runbook's §11.28) | Ten replicas; the final tree (PI-024) | F14, E26 |
| 15 | A recovery process replays previously processed messages | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterDatabaseTest.java#tenReplaysOneRequeue` [db]; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterDatabaseTest.java#aReplayPastTheWindowIsRefused` [db]; `app/src/test/java/com/finapp/app/architecture/PlatformRehearsalContractTest.java#theRestoresBrokerStepRunsOnARealBroker` [hermetic], the harness's text for a step that never ran | A replay after a restore: `OPERATIONS_RUNBOOK.md` §11.35 step 6 never ran | F15 |

### 4.2 The scenario experiments

One per scenario, each run by `P16-TSK-027` under the demonstration workload, and under the engineering workload for scenarios
7, 8 and 14, which run at ten replicas (its backlog entry). Each counts the seven checks of §1.3.

| ID | Scenario | Hypothesis | Affected | Expected behaviour | Safety | Success criteria | Stop | Instances | Runs in | Result | Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F01 | Scenario 1: a transfer committed, its HTTP response lost: the edge's connection to the client reset after the command's commit (Toxiproxy on the client hop), the client retrying through another replica | The retry under the same key answers the stored result: one transfer, one journal entry | transfers, `platform` idempotency, the edge | The first answer lost; the retry answers it again, with the same transfer | Toxiproxy on the client hop only, removed at stop | The seven checks; (a) and (f) counted on every retried key | A second transfer for one key | 3 replicas at least | `P16-TSK-027` | — | — |
| F02 | Scenario 2: the provider simulator records a success and its answer is dropped (E15 on the provider hop), at load | The payment rests `UNKNOWN`; the sweep's inquiry resolves it to captured once; the capture is never resent | payments, the provider simulator | `PaymentUnknownAged` stays within its bound; the sweep resolves every `UNKNOWN` | The simulator only; the toxic removed at stop | The seven checks; 0 captures resent; every `UNKNOWN` resolved once | A second capture for one payment | 3 replicas at least | `P16-TSK-027` | — | — |
| F03 | Scenario 3: the brokers lost while postings continue (E09), and a relay pod killed between the broker's acknowledgement and the mark | Every outbox row survives and is published, and its effect is applied once | the outbox relay, Kafka, every consumer | Rows wait, then drain; the killed relay's row is sent again and deduplicated | The test-only brokers | The seven checks; every outbox row published; every effect once | An outbox row lost, or a second effect | 3 replicas at least | `P16-TSK-027` | — | — |
| F04 | Scenario 4: consumer pods killed during the sustained profile, timed to the commit window (E01) | A record whose effect committed is redelivered and not applied twice; one whose effect did not commit is applied once | every consumer group | Redelivery after the rebalance; deduplicated records counted | One pod at a time | The seven checks; 0 second effects | A second effect counted | 3 replicas at least | `P16-TSK-027` | — | — |
| F05 | Scenario 5: duplicates sent (E22), and the fleet scaled 3 → 10 → 3 during load | The inbox applies each record once across separate processes and rebalances | every consumer group | Rebalances counted; duplicates counted as deduplicated | Inside the dedupe window | The seven checks; 0 second effects | A second effect counted | 3 and 10 replicas | `P16-TSK-027` | — | — |
| F06 | Scenario 6: a replica killed during month-start repayments at load | A repayment commits whole or not at all; a retry by key repays once; every loan stays provable | lending | Retries answered; the loan subledger proof and `LOAN_REPLAY` hold after | One pod at a time | The seven checks; the loan proofs `HOLDS` over at least one loan each | A loan proof failing | 3 replicas at least | `P16-TSK-027` | — | — |
| F07 | Scenario 7: the same capture sent to ten replicas at once over distinct connections through the edge, a webhook racing it | One capture and one journal entry, whichever replica wins; the racing webhook adds nothing | payments | One winner; the rest answer the stored result or a conflict | The simulator only | The seven checks; one entry per payment | A second entry for one payment | 10 replicas | `P16-TSK-027` | — | — |
| F08 | Scenario 8: ten replicas releasing one hold at once, and a release racing the hold's expiry | One release and one dispatch; availability restored exactly once | payments, ledger holds | One release wins; the others answer it; a concurrent ledger-hold case added if the run shows the gap | The run's own customers and holds | The seven checks; one release per hold; availability restored exactly | A second release or a second dispatch | 10 replicas | `P16-TSK-027` | — | — |
| F09 | Scenario 9: a replica killed mid-parse of a source's file while the source's next file arrives | One acceptance at a time per source; the gapless sequence kept; the interrupted parse finished by another replica | settlement | The second file waits for the first; one live batch per file | Simulated settlement files only | The seven checks; one live batch per file; each source's sequence gapless | Two live batches for one file, or a gap in a source's sequence | 3 replicas at least | `P16-TSK-027` | — | — |
| F10 | Scenario 10: a replica killed mid-chunk of a reconciliation run | Another instance resumes from the last committed cursor; nothing is matched twice | reconciliation | The run completes on another replica | One pod at a time | The seven checks; the run complete; no allocation doubled | An allocation doubled, or the run abandoned | 3 replicas at least | `P16-TSK-027` | — | — |
| F11 | Scenario 11: the database primary failed over (E05) under the demonstration workload | Nothing acknowledged is lost; retries by key take one effect | PostgreSQL, every replica | Replicas unready, then ready on the new primary | The test-only pair (owner decision C7) | The seven checks; 0 acknowledged commits lost | The standby not caught up when the promotion is due | 3 replicas at least | `P16-TSK-027` | — | — |
| F12 | Scenario 12: Kafka unavailable (E09) under the demonstration workload | Money still moves; every event is published once after the return | Kafka, every consumer | The outbox waits, then drains | The test-only brokers | The seven checks; the outbox drained | The outage past 30 min | 3 replicas at least | `P16-TSK-027` | — | — |
| F13 | Scenario 13: the simulator times out, then sends success 5 min later, after the sweep's inquiry and after a resolved failure | The timeout leaves `UNKNOWN`; the late success is evidence, applied once or raised as a break, never a second effect. On the instant rail, PI-019's known double credit is recorded as a known exception, never as a pass | payments, the provider simulator, reconciliation | The late report recorded as evidence; a contradiction of a resolved failure raised as a break | The simulator only | The seven checks; (g) the contradiction raised as a break | A second effect, or a contradiction raised as nothing | 3 replicas at least | `P16-TSK-027` | — | — |
| F14 | Scenario 14: a rolling deploy and a rollback at ten replicas (E26) under the engineering workload | N − 1 and N coexist through the roll; zero failed requests; one effect per key | `finapp-app`, the migrator | As the release scope, at ten | The run's own images, built in the run | The seven checks; `OPERATIONS_RUNBOOK.md` §11.28's assertions at ten | A failed request or a second effect | 10 replicas | `P16-TSK-027` | — | — |
| F15 | Scenario 15: the restore drill's broker step: the consumer groups reset to the restore instant and the window replayed | Each replayed record is applied once; the inbox dedupes every record already applied | `platform` inbox, Kafka, the restore | Replayed records counted as deduplicated or applied once | The run's own restored database and brokers | The seven checks; every effect once | A second effect counted | 3 replicas at least | `P16-TSK-024`, `P16-TSK-027` | — | — |

---

## 5. Designed-only

ADR-0127 decision 7 and owner decision C4: what needs a real environment is written here, never claimed as executed. Each row
names its local analogue, the experiment here that exercises the platform's half with real software. The owner accepts each
as designed-only at the phase's exit, or holds it open (`PHASE_GATES.md` §5 Phase 16, criteria 2 and 3). Its status is
`DESIGNED-ONLY` until then. After the owner decides, it reads `ACCEPTED as designed-only by the owner on <date>`.

| ID | Designed-only scenario | Why it cannot run here | Local analogue | Written by | Status |
|---|---|---|---|---|---|
| D01 | A managed database's Multi-AZ failover: the endpoint's move and its real interruption | No cloud account: the reference Terraform is never applied (`PHASE_16_PLAN.md` §1.1) | E05, F11: the test-only synchronous pair promoted under load | `P16-TSK-022`, `P16-DOC-001` | DESIGNED-ONLY |
| D02 | An availability zone's loss | One machine: a `kind` worker labelled as a zone is a container, not a zone | E04: a worker stopped | `P16-TSK-020`, `P16-DOC-001` | DESIGNED-ONLY |
| D03 | A managed broker's failover, of a broker or a zone | No cloud account | E08: one broker of three killed | `P16-TSK-013`, `P16-DOC-001` | DESIGNED-ONLY |
| D04 | A region's loss and a regional recovery | No second region and no account; one region, active-passive (owner decision C10) | No experiment of this register: the platform rebuilt from the repository and a backup, timed, is the local analogue (`P16-TSK-025`'s exercise) | `P16-TSK-025`, `P16-TSK-026`, `P16-DOC-001` | DESIGNED-ONLY |
| D05 | A real provider's outage, slowness or late answer | Every provider is simulated (`ROADMAP.md` §Non-Goals) | E17, E18, E19, E20, E21, F13: the simulators degraded | `P16-TSK-018`, `P16-DOC-001` | DESIGNED-ONLY |

---

## 6. What this register does not claim

- That an experiment ran before its Result cell says so. No experiment has run on the date this register was written.
- That a `kind` result says anything about a real environment's capacity, failover time or provider. The designed-only rows
  (§5) are exactly what it does not show.
- That a test listed in §4.1 ran fresh on this tree. Its tier says whether it runs per task.
- That the platform is ready for production.
