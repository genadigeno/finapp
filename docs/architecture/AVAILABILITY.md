# Availability: the Single Points of Failure and the Financial-Operation Matrix

*Written 2026-11-13 by `P16-TSK-023` (`PHASE_16_PLAN.md` §10; ADR-0144, `Proposed`; `THREAT_MODEL.md` §6 items 43 (g) and 66), on
master `ce1abd2e`. `AvailabilityMatrixTest` holds it on every build. Nothing here has run on a cluster: every experiment it names is
built and its run is owed under `POSTPONED_ISSUES.md` PI-025 (the owner's word of 2026-11-13, "we dont need long running checks and
tests"), or was run earlier and says so in its register row. No number below is a measurement, and nothing here calls the platform
ready for production.*

A service can be up while an operation must be refused to keep money correct, and an operation can be answered while part of what it
causes waits. This document says, per failure, which financial operations are refused, which are answered with their effects waiting,
which are served with a limitation, and which are untouched; which experiment checks each; and which single points of failure remain.

---

## 1. How to read this

**PostgreSQL is the only financial truth.** An operation may answer a success only after its authoritative record committed there, in
the command's own transaction with its idempotency record and its outbox row (`INV-EVT-01`, `INV-IDEM-*`). Kafka, the projections and
any cache are not truth (`CLAUDE.md` rule 12): Kafka is transport for rows the outbox already holds, and no cache exists
(`PHASE_16_PLAN.md` §3). So every verdict below is derived from three facts per operation: where its record lives (always PostgreSQL),
what else its answer waits on (a provider, never the broker: `NoDirectBrokerPublicationRulesTest` holds that only the outbox relay
publishes), and what the platform does when that dependency is away.

**The four verdicts.**

| Verdict | Means |
|---|---|
| `REFUSED` | Answered with an error and no record: nothing happened, and a retry under the same key is a first attempt |
| `QUEUED` | Answered with its authoritative record; whatever it publishes or causes downstream waits durably (the outbox, a sweep's due row) and completes once on the return. A row with no request (a schedule, the events) is `QUEUED` when its work stays due and is done once on the return |
| `DEGRADED` | Served with a stated limitation: some requests refused openly before any effect (shed, a provider that cannot be asked), an `UNKNOWN` resolved later by an inquiry and never by a resend, a read that may lag, a recorded fail-safe decision |
| `AVAILABLE` | Served as normal. A request in flight on an instance that is lost may fail once and is retried by its key to one effect |

**The rule the guard holds.** No request is `AVAILABLE` or `QUEUED` while its record cannot be written, and none is `REFUSED` for the
broker's sake. A row whose failure takes the financial record away (kind `record`) refuses every request and leaves schedules and events
only waiting or refused; a row whose failure takes the record's writes away (kind `record-write`) refuses every command and callback and
serves no read as normal; a row whose failure is the broker's (kind `transport`) refuses no request. The rows the database's experiments
check must be of kind `record` or `record-write`, the ones the brokers' experiments check of kind `transport`: the guard reads that from
each row's checks, not from its label alone.

**Checked by.** Each failure row names the experiments of `RESILIENCE_EXPERIMENTS.md` that take that failure away and whose success
criteria cover the row, and the designed-only rows (`D01`…`D05`) for what only a real environment can show. **Walked by** names the
experiments that compare the row itself with the platform's answers, operation by operation (§5).

---

## 2. The single points of failure

`PHASE_16_PLAN.md` §10.1's components, each with today's state on `kind` and in the reference design, and its answer. A component's
"single point?" is about the financial operations: whether its loss alone stops or misstates money. The guard holds that every component
of §10.1 has a row here, and that each row names its check: a registered experiment, a designed-only row, or `N/A: ` and its reason.

| Component | On `kind` | In the reference | Single point? | Answer | Checked by |
|---|---|---|---|---|---|
| Application | Three replicas at least, a disruption budget, spread over three zones in the experiments' cluster | The same, seven at most until the budget at ten (ADR-0134) | No: every replica runs every role and arbitrates in PostgreSQL (ADR-0014) | Matrix rows A07 and A08 | E01, E02, E03, E04 |
| Migrator | A Job per release; Flyway's lock serialises | The same | No for service: a failed Job blocks a release, not a request | `MigratorJobFailed`, `MigratorJobOverdue` (`OPERATIONS_RUNBOOK.md` §11.41) | N/A: not a failure of any operation: no request waits on the migrator, and a failed Job leaves the running release serving |
| PostgreSQL (reference) | — | A managed primary with a synchronous standby in another zone | No by design; its failover unmeasured here | Matrix row A01; the managed failover is designed-only | E05, D01 |
| PostgreSQL (`kind`) | One server on a volume claim in the deployed overlay; in the experiments scope a primary and a synchronous standby in another zone (owner decision C7) | — | **Yes** in every scope but the experiments' (test-only). After a promotion the new primary runs alone until a standby is rebuilt, and losing its volume then loses what it committed (ADR-0139) | The test-only pair, failed over by the drill (`OPERATIONS_RUNBOOK.md` §11.54); matrix row A01 | E05, E06, E57 |
| Kafka (reference) | — | Three brokers, replication 3, `min.insync.replicas` 2 | No | Matrix rows A05 and A06; a managed broker's failover is designed-only | E08, D03 |
| Kafka (`kind`) | One broker in the deployed overlay and in most scopes; three test-only brokers in the experiments and load scopes (`THREAT_MODEL.md` §6 item 58) | — | **Yes** where one broker runs, for publication only: the outbox holds every event, so money still moves | Matrix row A06: the outbox waits; three brokers in the experiments scope | E08, E09 |
| Edge | Two controllers per edge | The same, behind the cloud's load balancer | No within a cluster | Matrix row A09 | E25 |
| Telemetry | Two collectors; one Prometheus, one Alertmanager, one Tempo and one Loki, each on `emptyDir` | A real environment's highly available back ends (`THREAT_MODEL.md` §6 item 43 (c)) | **Yes for detection**, never for money: losing Prometheus or Alertmanager blinds the fleet - no page can fire - and on `kind` their history goes with them, so every burn window restarts empty | Matrix row A14; real high availability is a real environment's (this task's exclusion) | E72 |
| Registry | Every image loaded into every node, so a replacement never pulls | One registry (`T42`, accepted until 2027-06-30) | **Yes** in the reference for a starting replica; not for a running one | The disaster-recovery task's pre-pulled images and mirror's design. No matrix row: no running operation waits on it | N/A: no experiment takes the registry away: on `kind` nothing pulls at run time, and the reference's pull is T42's |
| DNS, the cluster's control plane | One control plane; CoreDNS | Managed | `kind`'s control plane is (test-only): a lost one schedules no replacement, while running replicas keep serving | Matrix row A15 for DNS; the control plane is designed-only | E16, D02 |
| A region | One | One region, active-passive for the record (owner decision C10) | **Yes** | Matrix row A18; ADR-0128's region tier, ADR-0129 | D04 |
| The simulated providers | One simulator per run, serving the card PSP, card tokenisation, the payout rail, the FX provider and the reference rates | One provider per rail | **Yes**, by the programme's non-goal: a second provider is a real environment's decision | Matrix rows A10 to A13 | E17, E18, E73, D05 |
| The audit archive's store | One test-only store in the experiments scope | An object store with write-once retention | No for money: rows wait in the archive's queue | Matrix row A16 | E44 |
| The WAL archive's store | One test-only store | The reference's backup storage | No for money; **yes for the recovery objective**: while it is away the point-in-time window stops growing | Matrix row A16; ADR-0128's tier 2 | E11, E60 |
| The cluster watcher | One replica | The same | No for money: it reads the migrator Job's state, and its own loss pages (`ClusterWatchBlind`) | N/A: not a failure of any operation | N/A: no operation reads it; its own loss is `ClusterWatchBlind`'s, and no experiment takes it away |

---

## 3. The operations

The financial operations, each a class of the routes the two edges serve. **The guard derives the classes' coverage from the edges' own
generated route lists** (`deploy/edge/base/public-routes.yaml`, `deploy/edge/base/operator-routes.yaml`): every served route belongs to
exactly one operation, and every pattern below serves at least one route. A pattern is its methods, comma-separated, and a path: `{name}`
is one segment; a trailing `/**` is the path and anything under it. An exact path beats any `/**`, and a longer `/**` a shorter. Two kinds
have no route: the scheduled work and the events.

| Op | Operation | Routes | Kind |
|---|---|---|---|
| O01 | Identity, sign-in and sessions: registration, authentication, factors, recovery, contact channels, consents, organisations | `POST /v1/registrations`, `POST /v1/authentications/**`, `DELETE /v1/sessions/**`, `POST /v1/recoveries/**`, `PATCH /v1/me`, `POST /v1/me/mfa/**`, `POST /v1/me/channels/**`, `POST /v1/me/credential`, `POST,DELETE /v1/me/consents/**`, `POST /v1/me/organisations`, `POST /v1/me/kyb/**` | command |
| O02 | KYC submission | `POST /v1/me/kyc/**` | command |
| O03 | Wallets, beneficiaries and payment methods: open and close a wallet, add a currency, attach a card or a bank account | `POST,DELETE /v1/me/accounts/**`, `POST,DELETE /v1/beneficiaries/**`, `POST,DELETE /v1/me/payment-methods/**` | command |
| O04 | Internal transfer | `POST /v1/transfers` | command |
| O05 | Top-up and card payment: create, confirm, cancel | `POST,DELETE /v1/payments/**` | command |
| O06 | Withdrawal | `POST /v1/me/withdrawals` | command |
| O07 | Checkout, card or BNPL | `POST /v1/checkout/**` | command |
| O08 | Merchant API: payouts, dispute answers, BNPL refunds and disputes | `POST /v1/merchant/**` | command |
| O09 | FX quote and conversion | `POST /v1/me/fx/**` | command |
| O10 | Cross-border payment and beneficiary | `POST /v1/me/cross-border/**` | command |
| O11 | Credit decision | `POST /v1/me/credit/**` | command |
| O12 | Lending: application, offer, draw, repayment, prepayment, payoff, closure | `POST /v1/me/loan-applications/**`, `POST /v1/me/loan-offers/**`, `POST /v1/me/loans/**` | command |
| O13 | BNPL: application, offer, repayment, payoff, dispute | `POST /v1/me/bnpl-applications/**`, `POST /v1/me/bnpl-offers/**`, `POST /v1/me/bnpl-agreements/**`, `POST /v1/me/bnpl-disputes/**` | command |
| O14 | Provider callbacks | `POST /v1/providers/**` | callback |
| O15 | Reads: balances, statements, payments, the merchant's and the operators' views | `GET /**` | read |
| O16 | Settlement and reconciliation, by operators: a file landed, attested or declined, a break resolved, a run replayed | `POST /v1/operator/settlement/**`, `POST,DELETE /v1/operator/reconciliation/**` | command |
| O17 | Every other operator command: adjustments, reversals, refunds and voids, payouts, four-eyes approvals, terms and policies, roles, replays and dead letters | `POST,PUT,DELETE /v1/operator/**`, `POST,DELETE /v1/ledger/**`, `POST,DELETE /v1/identities/**`, `POST /v1/kyc/**`, `POST /v1/payments/{id}/refund`, `POST /v1/payments/{id}/void`, `POST /v1/transfers/{id}/reversal` | command |
| O18 | Scheduled financial work: the sweeps and their inquiries, accruals and collections, settlement pulls and parses, reconciliation runs, report generation, the retention and archive sweeps | — | schedule |
| O19 | Events: the outbox relay and the consumers' reactions (a registration's KYC case, lending's and BNPL's origination hints, risk's signals) | — | events |

---

## 4. The matrix

One row per failure; each operation once per row. Where the plan's §10.3 gave an example, its row is here (the primary lost: A01;
Kafka lost: A06; a provider lost: A10 to A13; FX rates stale: A12; the trial balance non-zero: A19; a restore under way: A20).

| Row | Failure | Kind | Checked by | Walked by | REFUSED | QUEUED | DEGRADED | AVAILABLE | What the client and the money see |
|---|---|---|---|---|---|---|---|---|---|
| A01 | PostgreSQL's primary lost, restarted or unreachable | record | E05, E06, E57, F11; D01 | E05, E06 | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17 | O18, O19 | — | — | Every replica goes unready after three failed checks ten seconds apart and the edge answers `503` with no endpoint; before that a replica answers `500 api.InternalError` (`THREAT_MODEL.md` §6 item 61 recommends `503` with `Retry-After`), never a success. No replica restarts. A schedule's work stays due; an event waits in the outbox, or in the broker when it was published and not yet applied, and the inbox applies it once on the return. After a failover the promoted primary runs alone until a standby is rebuilt (ADR-0139) |
| A02 | PostgreSQL slow, its connections exhausted, or its link lossy | record-slow | E07, E12, E13, E51 | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | — | A public request the pool cannot serve in time is shed with `503 api.Overloaded` and `Retry-After` before its idempotency claim (ADR-0137); the others wait up to the pool's 5 s and their statements' bounds. A statement cut by a reset rolls back whole. Provider callbacks and the operator surface are not shed and wait at the pool. An operator's reserved connection is admitted (E07) |
| A03 | PostgreSQL out of space | record-write | E24 | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O16, O17 | O18, O19 | O15 | — | Every write fails at commit and its request answers an error with no record; a read that writes nothing is served, one whose door counts or whose session is touched fails with the write (unmeasured until E24's run). Schedules and events wait until space returns |
| A04 | One replica cut from PostgreSQL | instance | E14 | — | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | The cut replica goes unready and its in-flight requests fail, each retried by its key on another; a connection the cut holds is given up at the data source's socket timeout (ADR-0139); its leases lapse and are taken |
| A05 | One broker of three lost | transport | E08; D03 | — | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | Partitions elect new leaders with two copies in sync; a publication caught in the move is retried by the relay from the outbox |
| A06 | Every broker lost | transport | E09, F03, F12 | E09 | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O16, O17, O18, O19 | O15 | — | Every replica stays ready: a broker that is merely down is in no readiness check. Every command answers with its record and its events wait in the outbox, inside the relay's retry window; `OutboxPublicationStalled` pages and `event_freshness` burns. The consumers' reactions wait: a registration's KYC case is opened by the customer's own submission instead (`CustomerOpenedOpensCase` converges), lending's and BNPL's origination steps by their sweeps' next permit, risk's signals on the return - so a view they feed lags |
| A07 | One application replica lost, a pod evicted, or a node lost | instance | E01, E03, E04; D02 | — | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | The others serve; requests in flight on the lost one fail and are retried by key; its claims lapse and are taken; a graceful eviction drains first |
| A08 | Half the application fleet lost at once | instance | E02 | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | — | The rest carry what they can and shed the public rest before any effect; the disruption budget keeps one ready; the autoscaler replaces |
| A09 | An edge controller lost | edge | E25 | — | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | The second controller serves; connections on the lost one fail once and are retried by key |
| A10 | The card provider unavailable, silent or slow | provider | E17, E21, F02, F13; D05 | — | — | — | O05, O07, O08, O17, O18 | O01, O02, O03, O04, O06, O09, O10, O11, O12, O13, O14, O15, O16, O19 | A card confirmation answers `503` when the provider cannot be asked and rests `UNKNOWN` when it is silent; the sweep's inquiry concludes it once on the return, never a resend (`TIMEOUTS_AND_RETRIES.md` §1). Dispute answers, an operator's refund or void, and the card sweeps' inquiries wait the same way |
| A11 | The payout rail or card tokenisation unavailable or silent | provider | E73; D05 | E73 | — | — | O03, O08, O17, O18 | O01, O02, O04, O05, O06, O07, O09, O10, O11, O12, O13, O14, O15, O16, O19 | A payout answers `503` or rests `UNKNOWN`, its amount held, and the payout sweep's inquiry concludes it once on the return. Attaching a card is refused with nothing stored and is attached when retried; an operator's destination proposal likewise |
| A12 | The FX provider or the reference rates unavailable, the rates going stale | provider | E18; D05 | — | — | — | O09, O10, O18 | O01, O02, O03, O04, O05, O06, O07, O08, O11, O12, O13, O14, O15, O16, O17, O19 | Quotes are refused once the reference is past its staleness; no conversion is booked on a stale rate (`INV-FX-*`); same-currency movements are untouched |
| A13 | A provider no simulator on `kind` serves: the instant scheme, a corridor, the KYC provider, a credit bureau, a settlement report source | provider | E19, E20; D05 | — | — | — | O03, O05, O06, O10, O11, O12, O13, O16, O17, O18 | O01, O02, O04, O07, O08, O09, O14, O15, O19 | Each adapter reads an unanswered call as its own "we do not know" and its sweep inquires (`TIMEOUTS_AND_RETRIES.md` §2.1): bank accounts, pay-by-bank top-ups, withdrawals, a loan's payout and an operator's instant refund wait on the instant scheme; cross-border payments on their corridor and their beneficiaries' screening; credit and BNPL decisions take the recorded fail-safe, never an approval on missing data; a settlement pull is retried at its next permit. **Designed-only**: E19 cannot be driven (`THREAT_MODEL.md` §6 item 60) and E20 rests on the recorded fail-safe; the others have no simulator (item 66 (b)) |
| A14 | The telemetry back ends lost: Prometheus and Alertmanager | dependency | E72 | E72 | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | Money moves as before: no operation reads them and they are in no readiness check. The platform is blind - no page fires while they are away - and on `kind` their history goes with them |
| A15 | The cluster's DNS lost | dependency | E16 | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | — | Established connections continue; a new one to the database, the broker or a provider fails until the resolver returns, so a pool that must grow and a provider call on a new connection fail openly |
| A16 | An object store lost: the audit archive's or the WAL archive's | dependency | E11, E44, E60 | — | — | — | O18 | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O19 | The audit archive's queue grows and nothing is marked before its copy is read back; the WAL archive waits, so the point-in-time window stops growing until it returns |
| A17 | An availability zone lost | zone | D02 | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | — | The replicas in the other zones serve; if the database's primary was in it, row A01 holds until the managed failover completes. Designed-only: a `kind` worker is a container, not a zone |
| A18 | The region lost | record | D04 | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | — | — | — | Everything refused until the recovery site serves from the last copy (ADR-0128's region tier, ADR-0129); what the region held since that copy is reconciled at the post-recovery gate. Designed-only |
| A19 | A correctness signal pages: the trial balance non-zero, a projection drifting, a dead letter | signal | E68 | — | — | — | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19 | Nothing refuses by itself: a freeze of money movement is a person's recorded decision (`OPERATIONS_RUNBOOK.md` §11.20 and §11.12). Whether any flow should refuse automatically is §6's proposal, for the owner (`THREAT_MODEL.md` §6 item 66 (a)) |
| A20 | A restore under way | record | E59, E61 | — | O01, O02, O03, O04, O05, O06, O07, O08, O09, O10, O11, O12, O13, O14, O15, O16, O17 | O18, O19 | — | — | The restored database refuses the platform's logins until its financial verification passes and a person releases it (`OPERATIONS_RUNBOOK.md` §11.35 and §11.55), so no money moves on unverified data; the window since the restore point is reconciled with money held |

---

## 5. How the experiments check it

**Each row is checked by the experiments it names**, through their own success criteria, which cover the row's mechanism: readiness
falling and no money-moving success while the database is away (E05, E06), connections given up (E57), shedding before any claim (E51),
the outbox holding every event (E09), a card movement concluded only on the provider's answer (E17), no conversion on a stale rate (E18),
and the census after every one. Rows A13, A17 and A18 are designed-only (`RESILIENCE_EXPERIMENTS.md` §5).

**Five experiments walk their row** (`infra/scripts/kind-rehearsal/experiments.sh`, `availability_check`): E05 and E06 (A01), E09 (A06),
E72 (A14) and E73 (A11). After the fault each reads every answer of the public edge from the minute before it, takes each answer that
ended inside the fault's window to the operation whose pattern of §3 matches its method and path most specifically, and compares it with
the row read from this document:

- `REFUSED`: no success from the application. One success is a mismatch.
- `QUEUED`, `AVAILABLE`: at least one success once any answer was seen. Answers and no success is a mismatch.
- `DEGRADED`: answered; what makes it safe is the experiment's own criteria (E73's payouts concluded on the provider's record, for one).
- An operation the workload did not send in the window is recorded as not exercised, never as a pass. A window in which a row claiming
  `QUEUED` or `AVAILABLE` exercised nothing could not show the row, and the experiment fails.

A mismatch fails the experiment, and its recovery and census still run: the platform and this document disagree, and one of them is
wrong. The walk compares what the rehearsal's workload sends (sign-ins and registrations, transfers, top-ups and their reads, checkouts,
payouts) and E73's card attach; the other operations' verdicts rest on the same mechanisms and on the rule §1 states, never on a walk.

**The probe** (`REHEARSAL_PROBE=matrix-mismatch`, with E06): the run's own copy of this document claims internal transfers (O04)
`AVAILABLE` while the database is away (A01), the one row where every operation in fact refuses; E06's walk must fail on that row, and
a run that sent no transfer in the window could not show it. The repository's copy is never changed. Owed under PI-025, with every
run above.

---

## 6. A correctness signal: should anything refuse automatically?

`PHASE_16_PLAN.md` §10.3 left a question: when a correctness signal pages - the trial balance non-zero, a projection drifting from the
journal, the GL apart from the ledger - should any flow refuse money movement by itself? Today none does: the page wakes a person, and the
freeze is that person's recorded decision (`OPERATIONS_RUNBOOK.md` §11.20, §11.12). The proposal, for the owner (`THREAT_MODEL.md` §6
item 66 (a), ADR-0144 decision 5):

- **(i) Keep the freeze a person's act** (recommended). The postings themselves cannot unbalance: every journal entry balances at its
  write, held by the database. A non-zero trial balance therefore means a fault outside the write path (a raw write, a restore, a defect
  in a projection's read), which a person must find; an automatic freeze would not find it. The signals are gauges every instance
  computes from the database on a schedule, and their own failure - a stale or unreadable gauge, `CorrectnessUnverifiable` - would then
  close every door: monitoring would become the outage. And stopping every customer's access to their own money is itself a harm that
  needs a person's judgement. The one automatic refusal that exists is the right shape for an automatic one: a restored database refuses
  the platform's logins until its verification passes (row A20), enforced by the database, not by a gauge.
- **(ii) A narrow automatic hold**, once the signals' false-alarm rates are measured: a page scoped to one product or currency suspends
  only the commands that would post there, written as the same audited fact a person writes and confirmed by an operator within a bound -
  the stance owner decision C12 took for a rail's availability (`TIMEOUTS_AND_RETRIES.md` §5).
- **(iii) Refuse all money movement on any correctness page.** Not recommended, for (i)'s reasons.

---

## 7. The availability objectives at the sustained profile

`SLOS.md` §2's availability objectives - `authentication_availability`, `transfer_availability`, `payment_checkout`, `merchant_api`,
`provider_callbacks` and `operator_surface` - are read over a load run's whole window from the platform's own SLI rules
(`infra/prometheus/rules/slo-recording.yml`: each objective's five-minute error ratio, taken over the run instead), beside each
objective's target and the share of its 30-day budget the run spent: `load/slo-availability.tsv`, written by every `load` run
(`load_availability_objectives`). At the sustained profile that reading is E74. **It is recorded, never judged here**: a load run fails on
correctness only (`PHASE_16_PLAN.md` §5.2-§5.3), and validating or recalibrating each objective is `P16-TSK-029`'s. Not run: owed under
PI-025.

---

## 8. What this does not claim

- That any row was walked or any experiment run since this document was written. E01 ran before it, at three replicas, and checked
  A07's mechanism, not the walk.
- That a `kind` result says anything about a managed database's, a managed broker's or a real provider's availability (`PHASE_16_PLAN.md`
  §1.1; rows A13, A17 and A18 and `RESILIENCE_EXPERIMENTS.md` §5).
- That the walk covers every operation: it covers what the workload sends; the others rest on the mechanism and on §1's rule, which the
  guard holds over every row.
- That the telemetry back ends, the registry or the region are made highly available here: they are registered, and their answers are a
  real environment's.
