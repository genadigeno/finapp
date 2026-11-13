# Service-level objectives and correctness signals

*Written by `P15-TSK-025` (2026-11-08). Decided by ADR-0124 and owner decision C10; the objectives are `PHASE_15_PLAN.md` §11.1's.*

This document says which signals have a budget and which do not. An **objective** spends an error budget: some failure is expected,
and the budget says how much. A **correctness signal** has no budget: a ledger out of balance is an incident at its first sample,
because "99.9% balanced" means nothing (ADR-0124 decision 4). Keep the two apart. The alerts are in `infra/prometheus/rules/`, the
procedures in `OPERATIONS_RUNBOOK.md`, and `AlertCatalogueTest` holds this document and the rules to each other.

Every target is a **reference value**. Phase 16's load model recalibrates them *(2026-11-13: §6 says how each is judged under load, by
runs owed under `POSTPONED_ISSUES.md` PI-025; none is validated or recalibrated yet, and §7 holds the indicators proposed beside them)*. Nothing here is a commitment to a customer, and no
compliance is claimed by anything in it.

## 1. How an objective is measured and alerted

`infra/prometheus/rules/slo-recording.yml` records one error ratio per objective over four windows: 5m, 30m, 1h and 6h. Every ratio is
the fleet's. `infra/prometheus/rules/slo.yml` alerts on two pairs of windows, and a pair needs both of its windows over the threshold:

| Pair | Windows | Threshold | Severity | What it says |
|---|---|---|---|---|
| Fast | 1h and 5m | 14.4 × the budget | `page` | Two per cent of a 30-day budget gone in an hour |
| Slow | 6h and 30m | 6 × the budget | `ticket` | Five per cent of a 30-day budget gone in six hours |

The long window says the budget is really burning. The short window says it is burning now, so an incident that stopped stops paging at
once. Two windows, each over the threshold, need no long `for`: the fast pair holds 2 minutes and the slow pair 15, only to
ride out one scrape's noise.

## 2. The objectives

| Objective | SLI | Target over 30 days | Owner | Error budget |
|---|---|---|---|---|
| `authentication_availability` | Non-5xx ratio of `/v1/authentications`, `/v1/authentications/mfa` and the `/v1/sessions` routes | 99.9% | identity | 0.001 |
| `authentication_latency` | Requests on those routes within 1 s | 95% within 1 s | identity | 0.05 |
| `transfer_availability` | Non-5xx ratio of `POST /v1/transfers` | 99.9% | transfers | 0.001 |
| `transfer_latency` | `POST /v1/transfers` requests within 1 s | 99% within 1 s | transfers | 0.01 |
| `payment_checkout` | Non-5xx ratio of the payment and checkout commands (every method but `GET` on `/v1/payments/**` and `/v1/checkout/**`) | 99.9% | payments | 0.001 |
| `merchant_api` | Non-5xx ratio of `/v1/merchant/**` | 99.9% | merchant | 0.001 |
| `provider_callbacks` | `5xx ÷ (2xx + 5xx)` of `/v1/providers/**`, accepted or deduplicated counted as success | 99.95% | payments | 0.0005 |
| `operator_surface` | Non-5xx ratio of `/v1/operator/**` | 99.5% | platform | 0.005 |
| `event_freshness` | Share of minutes in which no publishable outbox row was older than 60 s | 99% of minutes | platform | 0.01 |
| `consumption` | Share of minutes a consumer group's lag stayed under 1000 records, per group | 99% of minutes | platform | 0.01 |

**Where each number comes from, and what it means.**

- **A provider's answer is never an error.** A declined or an unknown payment is a modelled outcome and answers `2xx` or `4xx`, so it spends
  no budget. Only a provider that could not be asked at all answers `503`, which is the platform being unable to serve.
- **A forged callback spends nothing.** A delivery refused as unauthentic or stale answers `4xx`, which is excluded from the
  denominator, so nobody outside can spend the budget. `finapp.payments.webhook{outcome="refused"}` has its own ticket instead.
- **"Within the route's documented objective"** in `PHASE_15_PLAN.md` §11.1 had no documented figure for authentication. This table is
  it: 95% within 1 s, the credential hash's cost included.
- **Latency buckets.** `http.server.requests` publishes no buckets by default. `SloLatencyBuckets` adds one boundary, 1 s, to every route of
  `http.server.requests` *(widened 2026-11-09 by the kind rehearsal, `P15-TSK-012`: to the routes named above alone, the timer without buckets beside the
  timer with them made one metric family both a histogram and a summary, and every scrape answered 500 once an instance had served both)*. Two bucket
  series per route and status, `le="1.0"` and `le="+Inf"`, on every route, where before only the routes named above carried them.
- **Event freshness is time-based**, not per row. A row counted when it is published cannot report a relay that has stopped, which is
  the failure this objective exists for. It reads `finapp.outbox.oldest.publishable`, which leaves out a row that is itself dead-lettered: a
  dead letter pages on its own (section 3), and would otherwise spend this budget for as long as it stood.
- **Consumption reads `finapp.inbox.lag`**, the sum over a group's members of the records awaiting each, from the client's own fetch state.
  The bound is a reference value. A consumer whose polls throw publishes NaN, not its last number: `InboxPollFailing` and
  `InboxPollRefused` page for that, since a lag that nobody can read cannot burn.

**Multi-instance.** Each rule states its aggregation in an `# Aggregation:` line beside its expression, and `AlertCatalogueTest` compares the
two. Counters are per instance, so a ratio sums errors and totals over every instance before dividing. A fleet-wide gauge is read by every
instance from the same database, so it is `max()`-ed, and `max()` ignores a NaN instance without either hiding or faking a breach. `sum()` does
not ignore NaN, so a summed gauge filters `x == x` first. A boolean comparison on NaN reads 0, a good minute nobody measured, so a time-based
SLI filters it first too, and an unmeasured minute is neither good nor bad.

## 3. Correctness signals: no budget, first sample

Each of these pages on its first breaching sample: severity `page`, `for: 0s`, and never in an SLO. The first sample means the first one
Prometheus holds: a gauge answers its last off-scrape read (ADR-0124 decision 7), so a rule reads the previous scrape's reading.

| Alert | Signal | Owner |
|---|---|---|
| `LedgerTrialBalanceNonZero` | The continuous trial balance per currency, `INV-ACC-01` | ledger |
| `LedgerProjectionDrift` | A balance projection differing from the journal, `INV-BAL-02` | ledger |
| `GlNotEqualLedger` | The close check `GL_EQUALS_LEDGER` failing, `INV-ACC-06` | accounting |
| `OutboxDeadLetter` | An abandoned outbox event still standing | platform |
| `InboxDeadLetterParked` | A parked inbox record (`finapp.deadletter.parked`) | platform |
| `PaymentWebhookUnmappable` | A signed callback unmapped, or denied, contradicted or unreadable on the provider's own inquiry (ADR-0083) | payments |
| `BnplThreeWayProofFailing` | The BNPL three-way proof | bnpl |
| `BnplSubledgerProofFailing` | The BNPL subledger proof | bnpl |
| `BnplReplayDiverged` | A BNPL schedule replay | bnpl |
| `CreditDecisionDiverged` | A credit decision replay | credit |
| `CreditExposureCensusFailing` | The exposure census | lending |
| `FxBooksUnexplained` | The FX books proof | fx |
| `FxPlanDiverged` | The FX plan replay | fx |
| `LendingSubledgerProofFailing` | The loan subledger proof | lending |
| `LendingReplayDiverged` | A loan replay | lending |
| `LendingCapitalProofFailing` | The capital proof | lending |
| `VelocityDiverged` | The risk velocity proof | risk |
| `RiskCensusDiverged` | A risk census | risk |
| `PositionProofFailing` | The reconciled position proof | reconciliation |
| `CashProofFailing` | The cash proof | reconciliation |
| `AccountingReportDiverged` | A `FINAL` report that does not reproduce | accounting |
| `JournalEntryUnsourced` | A journal entry with no domain record behind it, or whose record's amounts differ from its lines (the source-join census, `THREAT_MODEL.md` T15) | accounting |
| `GaugesStaleAcrossTheFleet` | Every instance's gauge canary stale at once: every fleet-wide `max()` rule on a gauge is blind (waits 5 minutes by the owner's ruling of 2026-11-09, the one row here with a `for`) | platform |

A correctness gauge that cannot be read is NaN, never a comforting zero. `CorrectnessGaugesUnreadable` names the instance (a ticket), and
`CorrectnessUnverifiable` tickets when no instance holds a number at all. A rule that reads a gauge carries an `x != x` rule beside it, and
`PlatformAlertRulesTest#everyGaugeAnAlertReadsCanBeSeenUnreadable` holds that across every rule file.

`ConfigFingerprintDisagrees` pages and is neither an objective nor a first-sample correctness signal, so it has no row here. Replicas disagree during every rollout by design, so a first sample means nothing and no budget measures it: only a disagreement that outlives the longest rollout an overlay allows is a fault, which is why it carries a `for`.

## 4. The error-budget policy

The budget is spent over a rolling 30 days. It is read from the burn-rate panels of the Grafana dashboard `finapp-slo`.

- **Budget left:** work as normal.
- **Budget spent, or on course to be spent before its 30 days:** the owner of that flow **freezes non-reliability changes to that flow** until the
  budget recovers. A frozen flow still takes reliability work, security fixes and anything that corrects money. The decision and the
  date are written into the incident record (`OPERATIONS_RUNBOOK.md` §11.8).
- **A correctness signal is not in this policy.** It is an incident whatever the budget says.
- **A budget spent by something outside the flow's control** (a provider's outage answering `503`) still counts. The freeze is then the owner's
  to lift with a reason written down.

This is a reference policy for a platform that has no customers yet (`ROADMAP.md`). Applying it needs a real on-call rota, which does not exist.

## 5. Routing and ownership

`infra/alertmanager/alertmanager.yml` routes by `severity` alone. The receivers are placeholders that send nothing, until an on-call tool
exists. `deploy/observability/alertmanager/alertmanager.yml` carries the same bytes. Every alert carries an `owner` label naming the owning
module, on the rule or on its group, and one runbook section that names it (`AlertCatalogueTest`).

`infra/scripts/alert-rules-check.sh` is the one way to check all of it, on a laptop and in CI's `alerts` job.

**What a deployed environment must keep.** The rules select `job="finapp"`, so a deployed scrape configuration keeps that job name. A deployed
scrape also labels its targets with no `env="local"`, which is how `CertificateExpirySeriesAbsent` tells a deployment from compose, and with an `env` naming its environment, because `ConfigFingerprintDisagrees` counts per `env` and two environments on one Prometheus without it would count as one fleet. cert-manager's
`certmanager_certificate_expiration_timestamp_seconds` has to be scraped for the certificate alerts to read anything. Each is recorded on the tasks that build the deployment.

## 6. Under load: how each objective is judged

*(Written 2026-11-13 by Phase 16's objectives task, ADR-0148, from `PHASE_16_PLAN.md` §15. **No run has judged anything yet**: every run
below is built, held by `ObjectivesRehearsalContractTest`, and owed under `POSTPONED_ISSUES.md` PI-025 by the owner's word of 2026-11-13.
No objective here is claimed met, and none was recalibrated.)*

**The judgement.** After the census, every `load` run writes `load/slo-objectives.tsv`: each objective of §2 read from the committed rules
at run time, never copied - its budget from its fast pair in `slo.yml`, its five-minute SLI from `slo-recording.yml` taken over the run's
window - with the burn rate the run spent it at (1 spends a 30-day budget exactly at its pace) and whether its fast or its slow pair fired
from the load's start until the judgement. **MET** is a burn rate of at most 1 with neither pair fired; **NOT MET** is either; **NOT READ**
is no sample, nothing having been sent to it. The availability objectives' requests and errors are also `load/slo-availability.tsv` (the
data tier's availability task's reading, E74). A run judges only under `REHEARSAL_LOAD_OBJECTIVES=1`: then an objective NOT READ, or a
window whose firing alerts do not show the migrator Job planted before every load (a silence nobody could have heard), stops the run as
unable to judge, and an objective NOT MET fails it. **A run never recalibrates an objective**: a target changes by a reviewed change to
`slo.yml` and this document together, with its reason written here.

| Run | What it judges | Owed |
|---|---|---|
| E84 | Every objective at the sustained profile (E74's run: the target's average for 30 minutes, then its peak for two hours): MET, each | PI-025 |
| E85 | A real burn: the database slowed under the workload until the fleet sheds (a shed is a `503` on its route, ADR-0137). Every objective whose burn pair's expression held for its `for` and a minute in Prometheus's own history must have fired, the page must reach its receiver, the fleet's `503`s must cover what it shed, and the fast pair must stop once the burn does | PI-025 |
| E86 | Business outcomes spend no budget: the target's peak with 30% of card authorizations declined by the provider; each refusal a decline, the payment objective's errors within what the client saw answered `5xx` or not at all, and E84's verdict over the run | PI-025 |
| E17 | Recorded, never judged: the payment objective's pairs during a provider's outage (below) | PI-025 |
| E75, E77 | The capacity calibration's runs (`OPERATIONS_RUNBOOK.md` §11.60) record `load/slo-objectives.tsv` as every load run does, and are judged by the same flag on the same run, `REHEARSAL_LOAD_OBJECTIVES=1`: at the scale `kind` carries (E75 a third of E30's step 3 with bursts at that step, E77 at E30's step 3 with the background replay), every objective is expected MET. Their latency and errors per stage stay their record, judged over the run and not per stage | PI-025 |

**Each objective, validated or recalibrated, and why.** None is either yet, because no run has judged one. What is known, and what the
runs will settle. One recorded run bears on E84 before it is made: the workload baseline's stress run on the reference machine (E30,
`CAPACITY_MODEL.md` §3.4) carried 0.39 of the target's peak within every objective at three replicas and broke at its next step, the
database's CPU the first limit. E84 at the target's peak on that machine is therefore expected to read NOT MET, its record naming the
machine's limit; that is no reason to recalibrate a production reference, and the record says so rather than the target moving.

| Objective | Judged by | Today |
|---|---|---|
| `authentication_availability`, `merchant_api` | E84; E85 if its routes shed | Kept. The load harness's development runs on the reference machine answered sign-ins and the merchant API `5xx` past what 99.9% explains while the machine's disk stalled for 5 to 25 s on WAL writes (`OPERATIONS_RUNBOOK.md` §11.43's record): a limit of one development machine, not of the platform, and no reason to loosen a production reference |
| `authentication_latency`, `transfer_latency` | E84; E85 | Kept: the only latency objectives adopted, each with its 1 s bucket (`SloLatencyBuckets`) |
| `transfer_availability`, `payment_checkout` | E84, E86; E85 | Kept. A decline, a risk refusal or an insufficient-funds refusal spends nothing (`infra/prometheus/tests/slo.test.yml`'s decline-wave case); a provider's outage spends nothing either, below |
| `provider_callbacks` | E84; the spike profile's run records it at the burst (E50, `load/slo-objectives.tsv`) | Kept: a callback is never shed (`@NotShed`) |
| `operator_surface` | E84 (the settlement upload and the operator reads) | Kept: never shed, by the edges' marker |
| `event_freshness`, `consumption` | E84 at peak; E09's outage reads the freshness ratio above zero | Kept |

**What the objectives cannot see.** Each is a finding, not a defect of a rule, and each has its own signal:

- **A provider's outage spends no budget.** A provider that answers `503`, or nothing, is an indeterminate answer to the adapter
  (`PspWireClient`): the payment rests `UNKNOWN` and its command answers the payment's state with a success status, as `INV-LIFE-03` asks.
  Only a provider that cannot be asked at all, an unconfigured one, answers `503`. So `payment_checkout`'s pairs stay silent through E17
  by design, and `PaymentUnknownAged` (`OPERATIONS_RUNBOOK.md` §11.22) is what tells. A processing indicator that would spend budget there
  is proposed in §7 (`THREAT_MODEL.md` §6 item 70).
- **A request the edge answers itself.** The SLIs are the application's request timer: the edge's own `429` (its per-source in-flight
  bound under a stall) and its `5xx` when no replica is ready never reach it. `EdgeErrorRatioHigh` (`OPERATIONS_RUNBOOK.md` §11.42) is
  their signal; a load run records them per operation in `load/k6/statuses.tsv`.
- **The balance read has no objective**, though it is the largest operation of the target (`PHASE_16_PLAN.md` §4.3). Proposed in §7.
- **Shedding spends the budget it protects.** A spike the platform sheds is a burn on every shed route (ADR-0137 decision 4); the spike
  profile's run records whether it pages (E50), which is the shedding thresholds' calibration, not the objectives'.

## 7. Indicators proposed, not adopted

*(Written 2026-11-13 by Phase 16's objectives task. Each is a proposal for the owner (`THREAT_MODEL.md` §6 item 70): nothing below has a
recording rule, a bucket or an alert, because `PHASE_16_PLAN.md` §15 adds a bucket only where a target is adopted. The targets are
`PHASE_16_PLAN.md` §4.3's proposed ones and the alerts' own bounds, never measured.)*

| Indicator | SLI it would read | Proposed target | What adopting it builds | Status |
|---|---|---|---|---|
| `payment_processing` | Card payments reaching a final state (`SUCCEEDED` or `FAILED`) within 15 minutes of their creation, over those created | 99% within 15 minutes, `PaymentUnknownAged`'s own bound | A counter of concluded card payments by whether they concluded within the bound, counted after commit | proposed |
| `account_read_availability` | Non-5xx ratio of `GET /v1/me/accounts/{id}/balance` and the account reads | 99.9% | Recording rules and burn pairs only: the request timer already carries the routes | proposed |
| `account_read_latency` | Balance reads within 300 ms | 99% within 300 ms | A 300 ms boundary on those routes in `SloLatencyBuckets` | proposed |
| `payment_latency` | Payment and checkout commands within 2 s, the simulated provider included | 99% within 2 s | A 2 s boundary on those routes | proposed |
| `merchant_api_latency` | Merchant API requests within 1 s | 99% within 1 s | The 1 s boundary on `/v1/merchant/**` | proposed |
| `settlement_ingestion` | Share of minutes in which no settlement file has waited more than 15 minutes from its arrival without being accepted, its second person's attestation left out (a person's wait, not the platform's) | 99% of minutes | A gauge of the oldest file waiting on the platform alone: `finapp.settlement.file.age` includes the attestation's wait | proposed |
| `reconciliation_completion` | Share of minutes in which no reconciliation run has been incomplete more than 30 minutes after its batch | 99% of minutes | Recording rules over `finapp.reconciliation.run.age`, which already measures from the run's creation | proposed |
| `ledger_posting_latency` | Postings within the posting's share of its command's latency | Read from the workload baseline's runs when they are made (E29, owed) | Buckets on `finapp.ledger.posting.latency` once a figure exists | proposed, no target |
