# ADR-0148 — The objectives judged under load, never recalibrated by a run; a real burn by shedding; the break-age counts

Status: Accepted (2026-11-16, `P16-DOC-001` — read against the code; no correction needed)
Date: 2026-11-13
Phase: 16
Context: Platform observability (the SLI rules, the burn alerts, the load and experiments harness) · Reconciliation's read side (breaks,
suspense)
Rests on: ADR-0124 (objectives and correctness signals), ADR-0072 (no amount or identifier in a metric), ADR-0126 (the workload model),
ADR-0127 (the failure experiments), ADR-0137 (a shed is a `503` on its route), ADR-0144 (the availability matrix and E74's reading);
`PHASE_16_PLAN.md` §15; `THREAT_MODEL.md` §6 item 42 (a), decided (i) on 2026-11-12.

## Context

`SLOS.md` §2 holds ten objectives, each with a 30-day budget and a fast and a slow burn pair (ADR-0124), every target a reference value
"that Phase 16's load model recalibrates". `PHASE_16_PLAN.md` §15 asks for each to be validated under load or recalibrated with its reason:
the burn rules firing on a real burn and silent at peak, the latency indicators proposed where a target is adopted, settlement ingestion and
reconciliation completion proposed, business outcomes shown spending no budget. The load harness ruled that a load run fails on correctness
only and records errors and latency by status (§5.2-§5.3), leaving the objectives' judgement here; the data tier's availability task records
the availability objectives over every run (`load/slo-availability.tsv`, E74 at the sustained profile). By the owner's word of 2026-11-13
no cluster runs in Phase 16 until after the phases (`POSTPONED_ISSUES.md` PI-025): the judgement is built and its runs owed.

Reading the code against the plan found three things the plan assumed otherwise:

- **A provider's outage spends no budget.** §15 has "the burn-rate rules fire on a real burn during E17". But the card adapter treats every
  answer that is not a parsed `200`, and every timeout, as indeterminate (`PspWireClient`): the payment rests `UNKNOWN` and its command
  answers the payment's state with a success status (`INV-LIFE-03`). Only an unconfigured provider answers `503`. E17 cannot burn
  `payment_checkout`, by design; `PaymentUnknownAged` tells.
- **The real burn exists elsewhere**: a slowed database makes the fleet shed before the handler, and a shed is a `503` on its route
  (ADR-0137 decision 4), which every request objective of a shed route counts.
- **The age alerts read the oldest alone** (item 42 (a)): while one parked break or suspense item stands, its rule stays firing and a newly
  aged one behind it raises nothing.

## Decision

1. **The judgement reads the committed rules at run time and never copies them.** Each objective, its budget (its fast pair's), its pairs
   and its five-minute SLI are read from `slo.yml` and `slo-recording.yml`; the SLI is taken over the run's window with its rates as
   increases. Every load run records each objective's error ratio, its burn rate (ratio over budget: 1 spends a 30-day budget exactly at its
   pace) and whether each pair fired, from the load's start until the judgement (`load/slo-objectives.tsv`).
2. **MET is a burn rate of at most 1 over the run with neither pair fired; NOT MET is either; NOT READ is no sample.** A run judges only when
   asked (`REHEARSAL_LOAD_OBJECTIVES=1`); then an objective NOT READ, or a window whose firing alerts do not include the migrator Job planted
   before every load, cannot be judged, and NOT MET fails the run.
3. **A run never recalibrates.** A target moves only by a reviewed change to `slo.yml`, `slo-recording.yml` and `SLOS.md` together, its reason
   in `SLOS.md` §6; never to silence a run, and never on a development machine's limit alone.
4. **Three runs, each owed under PI-025:** E84 judges every objective at the sustained profile (E74's run); E85 is a real burn, the database
   slowed by E51's toxic until a fast pair pages, judged from Prometheus's own history - every pair whose expression held for its `for` and a
   minute must have fired, at least one must have held, the page must reach its receiver, the fleet's `503`s must cover what it shed, and the
   fast pair must stop once the burn does; E86 is a decline wave at the target's peak, 30% of card authorizations declined by the simulator
   by their key, each refusal a decline the provider gave and the payment objective's errors within what the client saw. E17 records the
   payment objective's pairs during a provider's outage and never judges them.
5. **The latency, processing, read, edge, settlement-ingestion and reconciliation-completion indicators are proposed, not adopted** (`SLOS.md` §7):
   no bucket, recording rule or alert until the owner adopts one (`THREAT_MODEL.md` §6 item 70), since §15 adds a bucket only where a target
   is adopted.
6. **The break-age counts (item 42 (a), (i)):** `finapp.reconciliation.break.aged{severity}` and `finapp.reconciliation.suspense.aged`, the
   open breaks and the suspense items past the age rules' own bounds, counted from the same clock the age gauges use, so a count of at least
   one is exactly the age rule's expression holding (`AgedCountMetricsTest` reads the thresholds from the rule file). Beside each of the five
   age rules, one that fires when the count rises above its least over the last two hours while that least was at least one: one stood
   throughout, so the first to age stays the age rule's to raise. Same severity and `for` as the rule beside it; a window that outlasts every
   `for`; a resolution and a new aging within it still fire. Counts only (ADR-0072).

## Alternatives Considered

- **Judge every load run on the objectives.** Rejected: §5.3 rules that a load run fails on correctness only, and a development machine's
  disk stalls would turn every correctness run red for a reason that is not money.
- **Recalibrate from the first run's numbers.** Rejected: a target is a production reference; one machine's `kind` is not production
  (ADR-0126 decision 1), and the known stalls would loosen every objective for the machine's sake.
- **Make E17 the real burn by answering `503` on a provider's outage.** Rejected: it would turn an honest `UNKNOWN` into a refusal of a
  movement whose outcome is unknown, against `INV-LIFE-03`. The outage needs its own indicator (proposed), not a false error.
- **Count newly aged items with a new-in-window gauge** (breaks that crossed their bound in the last hour). Rejected for item 42 (a) as
  decided: the owner chose counts past the bound and a rule on their rise; the window's least gives the same signal from those counts.
- **Leave parked items out of the age rules** (item 42 (a) (iii)). Rejected by the owner's decision: it makes suspense a resting place.

## Consequences

- Positive: each objective has a judgement ready, read from the rules it judges, that cannot pass blind; a real burn proves the chain from
  a shed to a routed page; a decline wave proves a business outcome spends nothing; a newly aged break or suspense item behind a standing
  one is alerted.
- Negative: no objective is validated until the owed runs are made; the proposed indicators leave a provider's outage and balance reads
  without a budget until the owner adopts them.
- Neutral: five rules and two gauges more; every load run makes a few more Prometheus queries after its census.

## Invariants / Constraints

- Correctness signals stay unbudgeted and first-sample (`SLOS.md` §3; ADR-0124 decision 4).
- `INV-REC-05`: suspense is never a resting place - an aged item stays aged and alerted, and now each newly aged one is.
- `INV-LIFE-03`: an unknown provider outcome is never answered as a failure to make an objective see it.

## Follow-up

- E84, E85 and E86 at three replicas, owed under PI-025 (`OPERATIONS_RUNBOOK.md` §11.63).
- The owner's decision on `THREAT_MODEL.md` §6 item 70 (the proposed indicators).
- A target recalibrated, if a run shows one should be, with its reason in `SLOS.md` §6.
