# Service-level objectives and correctness signals

*Written by `P15-TSK-025` (2026-11-08). Decided by ADR-0124 and owner decision C10; the objectives are `PHASE_15_PLAN.md` §11.1's.*

This document says which signals have a budget and which do not. An **objective** spends an error budget: some failure is expected,
and the budget says how much. A **correctness signal** has no budget: a ledger out of balance is an incident at its first sample,
because "99.9% balanced" means nothing (ADR-0124 decision 4). Keep the two apart. The alerts are in `infra/prometheus/rules/`, the
procedures in `OPERATIONS_RUNBOOK.md`, and `AlertCatalogueTest` holds this document and the rules to each other.

Every target is a **reference value**. Phase 16's load model recalibrates them. Nothing here is a commitment to a customer, and no
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
- **Latency buckets.** `http.server.requests` publishes no buckets by default. `SloLatencyBuckets` adds one boundary, 1 s, to the routes
  named above and to no other, so the other routes' cardinality is unchanged.
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
scrape also labels its targets with no `env="local"`, which is how `CertificateExpirySeriesAbsent` tells a deployment from compose. cert-manager's
`certmanager_certificate_expiration_timestamp_seconds` has to be scraped for the certificate alerts to read anything. Those three are recorded on the tasks that build the deployment.
