# ADR-0124 — Observability, SLOs and error budgets: one telemetry egress, SLOs per critical flow with burn-rate alerts, correctness invariants never budgeted

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (observability) · every module (signals and runbooks) · Security (sensitive data in telemetry)
Rests on: ADR-0010, ADR-0016, ADR-0017, ADR-0018, ADR-0019, ADR-0034, ADR-0072, ADR-0120; owner decision C10 of
`PHASE_15_PLAN.md` §2.2 (ratified at its recommended default by the owner, 2026-11-07; *read "recommended default awaiting ratification" until then*).

## Context

The platform emits ECS JSON logs with correlation and trace ids. It has a Prometheus registry under a naming contract,
136 domain alerts in seven files proven by an in-repo evaluator, and spans that are recorded and then dropped because no
exporter is configured. Several gaps remain:

- No alert covers the ledger's continuous trial balance (`INV-ACC-01`), the outbox, HTTP errors or latency.
- No Alertmanager or routing exists.
- `promtool` has never read the rules.
- About sixty gauge classes read on the scrape thread.
- Latency objectives exist only as code.
- Identity, ledger, payments, transfers, checkout, merchant, kyc and consent have dashboard panels but no alert rule.

## Decision

1. **One egress.** The OpenTelemetry Collector receives OTLP traces and container logs. It scrubs any attribute outside
   an allow-list, applies the tail-sampling policy and exports to the backends: Tempo and Loki in the reference
   (compose and `kind`). Prometheus scrapes the management port. Alertmanager routes. Grafana is provisioned read-only.
2. **Sampling.** The application samples 100% of traces at the head. The collector keeps every error, every slow trace
   and every money-moving route, and samples the rest. Calibration belongs to Phase 16. The platform's traceability of
   a payment rests on the correlation id in the journal and the audit trail, not on a sampled trace.
3. **SLOs per critical flow.** The flows are authentication, internal transfer, payment and checkout, the merchant API,
   provider callbacks, event freshness, consumption lag and the operator surface. Each SLI is built from existing series
   with no new unbounded tag (ADR-0018). Each flow has a 30-day target. Burn rates are alerted in two windows: the fast
   pair pages and the slow pair raises a ticket. Recording and alert rules are checked by `promtool check` and unit-tested
   by `promtool test` in CI. An error-budget policy freezes non-reliability changes to a flow whose budget is spent.
4. **Correctness invariants are never budgeted.** Each of the following pages on its first sample and is never part of
   an SLO:
   - a non-zero continuous trial balance;
   - a failing product proof;
   - projection drift;
   - GL ≠ ledger;
   - an outbox dead letter.

   Money is either right or an incident.
5. **Every alert names its runbook section, and a test holds it.** Severity is `page` or `ticket`. The paging receiver
   is a placeholder until an on-call tool exists.
6. **No sensitive data in telemetry.** No amount (ADR-0072), name, instrument, credential or free text goes into a span,
   log field or tag beyond what ADR-0019 already permits. A rehearsal's exported spans and logs are swept for the planted
   needles of `DATA_CLASSIFICATION.md`.
7. **Gauges read off the scrape thread.** The `AccountingGaugeRefresher` pattern moves to `platform`, behind every gauge
   that reads the database. The refresher is made restartable (PI-014 item 2). A rule on `up` fires per instance.

   *(As built by `P15-TSK-024`, 2026-11-08; the decision is unchanged, and this note records how it was built.)*
   - **The refresher.** It is `platform`'s `GaugeRefresher` and `RefreshedReading`, framework-free. Its one thread's
     executor is made in `start()` and shut in `stop()`.
   - **Applied at the registry, not per class.** `app`'s `OffScrapeMeterRegistry`, the application's Prometheus
     registry, hands the function of every `finapp.` gauge, function counter and custom meter to the refresher. That
     covers the sixty database-reading gauge classes and the configuration gauges. A `finapp.` function timer is
     refused. Names, tags and help are untouched.
   - **What a reading answers.** No value before its first read and after a failed one. Once an ask has waited
     `finapp.gauges.stale-after` (2 minutes), no value until a read completes.
   - **The guard.** `NoGaugeReadsOnTheScrapeTest` renders every meter of the real context with the scrape thread
     watched.
   - **The rules.** `infra/prometheus/rules/platform.yml` holds `InstanceDown` (`up == 0` per instance, page),
     `FinappTargetsAbsent` (`absent(up)`, page) and `GaugesStale` (the refresher's canary, ticket), with `promtool test`
     cases.
   *(As built by `P15-TSK-025`, 2026-11-08; the decisions are unchanged, and this note records how they were built.)*
   - **The objectives.** `docs/architecture/SLOS.md` is the document; `infra/prometheus/rules/slo-recording.yml` and `slo.yml` are the rules. Fast pair 14.4× over 1h and 5m, paging; slow pair 6× over 6h and 30m, ticketing.
   - **Corrections to the plan's table, each stated in `SLOS.md`.** Event freshness is time-based on a new gauge, `finapp.outbox.oldest.publishable`; a per-row SLI counted at publication cannot see a relay that stopped. The provider-callback denominator excludes `4xx`, so a forged delivery spends no budget. Consumption reads a new per-module gauge, `finapp.inbox.lag`.
   - **Correctness.** Every proof and replay moved to `for: 0s` at severity `page`; the register is `SLOS.md` section 3, held by `AlertCatalogueTest`.
   - **Routing.** Alertmanager by severity to placeholder receivers that send nothing, in compose and `deploy/observability/alertmanager/`. Group-level `owner` labels on the domain files.
   - **Testing.** `promtool` and `amtool` run from digest-pinned images by `infra/scripts/alert-rules-check.sh` and CI's `alerts` job, each check refusing a planted defect.
8. **End-to-end tracing is proven on one real flow.** On `kind`, one payment's trace runs customer → request → domain →
   provider → ledger → settlement → reconciliation. The asynchronous legs are linked through the envelope's correlation
   and causation ids.

   *(As built by `P15-TSK-023`, 2026-11-11; decisions 1, 2, 6 and 8 are unchanged, and this note records how they were built and where the build
   corrects them.)*
   - **The egress.** `opentelemetry-exporter-otlp` with the JDK's HTTP sender (OkHttp excluded), pinned by the Spring Boot BOM, locked and
     checksum-verified; Boot builds the exporters only where the Deployment names the cluster's collector, so nothing else exports. Spans and log
     records leave through batch processors on their own threads with bounded queues: `TelemetryExportNeverBlocksTest` holds that a collector which
     never answers costs a request thread nothing, and the `kind` run measured it with the collector stopped (a refusal) and hung (a stand-in
     that accepts and never answers; the first run's "black-holed" phase was in fact a refusal, kube-router rejecting what no policy admits). At
     a stop the SDK's last export is bounded at 2 s in the ordered shutdown (`TelemetryFlush`), so a hung collector cannot spend the pod's grace.
     A public caller's `traceparent`, `tracestate` and `baggage` are removed at the public edge (ADR-0034's dated note), and since
     2026-11-12 an operator's at the operator edge (`P15-TSK-043`, ADR-0034's second dated note).
   - **Logs: the correction to "container logs".** A node agent reading `/var/log/pods` needs `hostPath` and root, which Pod Security `restricted`
     refuses on every namespace. So each replica ships the ECS line its console printed as an OTLP record's body (`OtlpLogAppender`), through the
     same one egress. The migrator Job's and the cluster components' own lines are not shipped; the node's rotation keeps them.
   - **The second net.** The collector (contrib 0.165.0, by digest) keeps an allow-list of attributes on resources, spans, span events and records,
     empties a span's status message, and scrubs a card number's shape and `Money`'s string form from bodies and values; `infra/scripts/telemetry-check.sh`
     plants needles through the committed configuration and requires none out, then removes the scrub and requires them out, in every CI run.
   - **Sampling under N collectors.** Two tiers in one Deployment: each replica routes spans by trace id to the peer that owns it, which holds the
     whole trace for the tail decision; errors, slow traces, writes and every asynchronous leg are kept, a tenth of the rest.
   - **The asynchronous legs as spans.** The relay's publication (`outbox.publish`) and each handled event (`inbox.handle`) are spans in the envelope's
     correlation and causation, recorded in `app` around the platform's own publisher and handlers; nothing in `platform` changed.
   - **The reconciliation leg: the correction to "one trace".** A run decides an item in the ingesting file's correlation, and its domain spans open
     before the leg restores even that (recorded at `P8-DOC-001`). So no span joins the run to the payment: each of the payment's requests is a correlation
     the platform mints (ADR-0034) holding its own legs, the file's upload and attestation two more, the intake's spans carry the file's id, the run's the source; the stored chain (expectation, allocation,
     item, run) joins them, as `PHASE_8_PLAN.md` §15 decided the durable trace would. `THREAT_MODEL.md` section 6 item 37 holds the choice.
   - **Backends.** Tempo 2.10 and Loki 3.7 on `kind` and in compose; a real environment's backends and their identity are not chosen.

## Alternatives Considered

### A vendor agent per signal
Cons: it adds several agents with privileges in every pod, and the telemetry egress becomes the vendor's choice. The
collector is vendor-neutral, and a vendor backend becomes an exporter.

### Head sampling at a low ratio
Cons: it loses exactly the rare failure someone needs to explain. A tail policy keeps that failure and still drops the
bulk.

### Budgeting correctness signals like availability
Cons: a "99.9% balanced ledger" is a contradiction.

## Consequences

Positive: every critical flow has an objective, an alert, a runbook and a trace. The most important correctness signal
finally pages.
Negative: a collector and two backends are added to operate. Burn-rate rules need unit tests to stay correct.
Operational impact: on-call reads a burn-rate page with a runbook link.
Security impact: the collector is a second scrubbing net, and telemetry egress happens in one place.
Financial impact: none to the rules. A financial breach becomes visible within one scrape.

## Invariants / Constraints

ADR-0018 and ADR-0072: no unbounded tag and no amount. `INV-ACC-01`'s signal pages. Liveness never reads a dependency.

## Follow-up

`P15-TSK-023`, `-024`, `-025` and `-032` build this ADR. Phase 16 calibrates targets and sampling under load.
