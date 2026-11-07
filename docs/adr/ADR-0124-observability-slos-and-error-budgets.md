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
8. **End-to-end tracing is proven on one real flow.** On `kind`, one payment's trace runs customer → request → domain →
   provider → ledger → settlement → reconciliation. The asynchronous legs are linked through the envelope's correlation
   and causation ids.

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
