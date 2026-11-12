# Capacity Model

*Written 2026-11-12 by Phase 16's first task, from `PHASE_16_PLAN.md` §4, §6.2 and §14 (ADR-0126, `Proposed`), on master
`6981c167`. This is the one place Phase 16 measures into. It states what the platform is asked to carry and where each number
comes from. The measured columns are empty until `P16-TSK-007` fills them. Nothing here is a capacity statement about a real
environment, and nothing here calls the platform ready for production.*

**How to read a number here.** Every number is one of three kinds, and the kind is always named:

- **Derived**: computed from the assumptions W1–W8 (§2), with the arithmetic shown. It is what a real environment would have to
  carry. It is never presented as measured.
- **Read**: read from the repository: the code, the configuration or a catalogue, with the file named. Re-read it when the file
  changes.
- **Measured**: produced by a run on the reference machine's `kind`, named with its workload, its machine, its date, its commit
  and its artefacts. Every measured cell below is `—` until `P16-TSK-007` fills it, and nothing is measured on this page yet.

A `kind` number is never presented as production capacity. The production target is never presented as measured (ADR-0126
decision 1).

---

## 1. Three workloads, never confused

| Workload | What it is | Where its numbers come from | What it proves |
|---|---|---|---|
| **Demonstration** | A small, fixed mix: 5 money movements a second for 10 minutes, with reads at ten times that (50 a second). The movements are split in the target's proportions (§3.1): transfers 26.5%, card or checkout payments 53.1%, top-ups and withdrawals 10.6%, BNPL purchases and instalments 9.3%, lending repayments 0.5% | Chosen to be carried by one development machine's `kind` with margin | The invariants hold **during** load, sampled every 30 s (`PHASE_16_PLAN.md` §5.3), and the harness works end to end |
| **Engineering test** | The highest sustained mix the reference machine's `kind` deployment carries within the latency objectives, found by stepping the arrival rate | **Measured** by `P16-TSK-007`, never chosen in advance | The platform's own first limit on this machine and the order in which resources saturate; the ratio of the production target to what was carried |
| **Proposed production target** | A launch-scale reference institution's traffic, from W1–W8 | **Derived** from stated assumptions (owner decision C8), each number traceable | What a real environment must carry. Never claimed as carried here: one development machine is not a production fleet |

A result is always reported with its workload's name.

---

## 2. The assumptions (W1–W8)

The owner adopted W1–W8 as the proposed production target on 2026-11-12 (`PHASE_16_PLAN.md` §2.2, C8, "accept
recommendations"). They are the owner's to change: a changed assumption changes every derived number that cites it, and
nothing else.

- **W1** 1,000,000 registered customers; 20% active on a given day (200,000).
- **W2** The busiest hour carries 10% of a day's volume. A **burst** is three times the busiest hour's rate for 60 seconds (a
  payday, a merchant's sale). A **spike** is ten times it for 10 seconds.
- **W3** Per active customer per day: 0.5 internal transfers, 1 card or checkout payment, 0.2 wallet top-ups or withdrawals,
  10 balance or account reads, 1.2 sign-ins.
- **W4** 2,000 merchants; two provider callbacks per payment; merchant API reads twice the merchant's payments.
- **W5** 20 settlement sources, four files a day each, 200,000 lines a day in all; one reconciliation run per file.
- **W6** 5,000 credit decisions a day. 50,000 active loans, with 2,000 repayments a day on average and 30% of a month's dues on
  its first day. 20,000 BNPL purchases and 15,000 instalments a day.
- **W7** The events each money movement writes: **read from the code, ≈ 7.3 on the target's mix** (§3.2). The plan wrote "four on
  average", an estimate this task was to replace by reading. The reading replaces it, and `PHASE_16_PLAN.md` §4.2 carries the
  erratum.
- **W8** Payloads: commands under 4 KiB; responses under 16 KiB; settlement files up to the platform's file bound; the request
  bound 1 MiB (`RequestSizeLimitFilter`).

**Readings this model makes where W1–W8 leave a gap.** Each is stated so the owner can change it:

- **R1 (W6)**: the 5,000 credit decisions a day are **loan** decisions. Every BNPL purchase carries its own credit decision
  (the code requests one per application, §3.2), so 20,000 BNPL decisions sit inside the BNPL purchases and are not part of
  the 5,000. If the owner meant 5,000 decisions in all, BNPL's purchases would have to fall to below 5,000.
- **R2 (W3)**: the 0.2 top-ups or withdrawals are split half and half, and top-ups are paid by card. Both a card top-up and a
  withdrawal write six events and post one journal entry (§3.2), so the split moves load between topics and leaves the totals
  unchanged. A bank-push top-up writes four.
- **R3 (W6)**: a month's dues are one instalment per active loan, so the month's first day carries 30% × 50,000 = 15,000
  repayments.
- **R4 (W5)**: one settlement line settles one expectation. That is the upper bound for the settled-expectation events (§3.2).
  A line that matches nothing settles none.
- **R5 (W4)**: W4 states no merchant payout frequency, so merchant payouts are not in the model. A daily payout per merchant
  would add about 2,000 payouts a day, small beside the rest. It is the owner's to add.

---

## 3. Per operation: the proposed production target

### 3.1 Rates

**Derived.** Rates are per second. *Average* is the day over 86,400 s. *Peak* is the busiest hour (W2: 10% of the day over
3,600 s, which is the day ÷ 36,000). *Burst* is three times peak, and *spike* is ten times peak. *In flight at peak* is
Little's law: the peak rate multiplied by the latency target. It is the number of requests the open-model generator (ADR-0126
decision 2) keeps outstanding at peak if every request takes its target. Latency and error targets are `SLOS.md`'s where an
objective exists. Elsewhere they are proposed here and validated by `P16-TSK-029`.

| Operation | Day | From | Average | Peak | Burst | Spike | In flight at peak | Latency target | Error target | Scaling |
|---|---|---|---|---|---|---|---|---|---|---|
| Internal transfer | 100,000 | W1 × W3: 200,000 × 0.5 | 1.16 | 2.78 | 8.33 | 27.8 | ≈ 3 (2.78 × 1 s) | 99% ≤ 1 s (`transfer_latency`) | non-5xx 99.9% | Horizontal; serialised per wallet pair by design |
| Card or checkout payment | 200,000 | W1 × W3: 200,000 × 1 | 2.31 | 5.56 | 16.7 | 55.6 | ≈ 11 (5.56 × 2 s) | p99 ≤ 2 s including the simulated provider (proposed) | non-5xx 99.9% (`payment_checkout`) | Horizontal; provider-bound |
| Wallet top-up or withdrawal | 40,000 | W1 × W3: 200,000 × 0.2 | 0.46 | 1.11 | 3.33 | 11.1 | ≈ 2 (1.11 × 2 s) | p99 ≤ 2 s (proposed) | non-5xx 99.9% | Horizontal |
| Account or balance read | 2,000,000 | W1 × W3: 200,000 × 10 | 23.1 | 55.6 | 167 | 556 | ≈ 17 (55.6 × 0.3 s) | p99 ≤ 300 ms (proposed) | non-5xx 99.9% | Horizontal; reads of the projection (ADR-0041), never a replica for a decision (`PHASE_16_PLAN.md` §6.6) |
| Sign-in | 240,000 | W1 × W3: 200,000 × 1.2 | 2.78 | 6.67 | 20.0 | 66.7 | ≈ 7 (6.67 × 1 s) | 95% ≤ 1 s (`authentication_latency`, the credential hash included) | non-5xx 99.9% | Horizontal; CPU-bound by the credential hash |
| Provider callbacks | 400,000 | W4: 2 per payment × 200,000 | 4.63 | 11.1 | 33.3 | 111 | provider-driven | accepted or deduplicated 99.95% (`provider_callbacks`) | as left | Horizontal; inquiries bounded by `CallbackInquiryBulkhead` |
| Merchant API | 400,000 | W4: 2 reads per payment × 200,000 | 4.63 | 11.1 | 33.3 | 111 | ≈ 11 (11.1 × 1 s) | p99 ≤ 1 s (proposed) | non-5xx 99.9% (`merchant_api`) | Horizontal |
| Settlement ingestion | 80 files, 200,000 lines | W5: 20 × 4 | — | one file per source at once | 20 files at once | — | 20 sources | a file accepted within 15 min of arrival (proposed) | no file lost; a refused file is evidence | Leaderless per file (`FOR UPDATE SKIP LOCKED`); one acceptance per source at a time (`settlement.source FOR UPDATE`) |
| Reconciliation | 80 runs | W5: one per file | — | 20 runs at once | — | — | — | a run complete within 30 min of its file (proposed) | no run abandoned | Chunked, resumable by any instance |
| Credit decision (loans) | 5,000 | W6, read R1 | 0.058 | 0.139 | 0.417 | 1.39 | ≈ 0.4 (0.139 × 3 s) | p99 ≤ 3 s (proposed) | non-5xx 99.9% | Horizontal; bureau-bound |
| Lending repayment | 2,000 (15,000 on a month's first day) | W6, read R3 | 0.023 | 0.417 (month start) | 1.25 | 4.17 | ≈ 1 (0.417 × 2 s) | p99 ≤ 2 s (proposed) | non-5xx 99.9% | Horizontal; serialised per loan |
| BNPL purchase or instalment | 35,000 (20,000 + 15,000) | W6 | 0.405 | 0.972 | 2.92 | 9.72 | ≈ 2 (0.972 × 2 s) | p99 ≤ 2 s (proposed) | non-5xx 99.9% | Horizontal; `LENDING_CAPITAL` a per-currency hot row (`PHASE_16_PLAN.md` §6.4) |
| Events written (outbox) | ≈ 3,213,000 | §3.2 | 37.2 | 89.3 | 268 | 893 | — | published within 60 s, 99% of minutes (`event_freshness`) | no dead letter | Relay on every instance, advisory lock per aggregate |
| Events consumed | per group, §3.3 | §3.3 | — | — | — | — | — | lag under 1,000 records, 99% of minutes per group (`consumption`) | no parked record | At most 12 consumers per group (12 partitions) |
| Ledger journal entries | ≈ 434,000 | §3.2 | 5.02 | 12.1 | 36.2 | 121 | — | the posting's share of its command's latency, measured (`P16-TSK-007`) | 0 refused for a platform fault | One `INSERT` path; hot rows ordered (`PHASE_16_PLAN.md` §6.4) |
| Database | — | — | — | — | — | — | at most the pool budget (§4) | statement p99 per top statement, measured | 0 deadlocks unexplained | Vertical for writes (§5) |
| Cache | — | — | — | — | — | — | — | — | — | **N/A**: no shared cache exists (`PHASE_16_PLAN.md` §3); the only caches are per instance and non-authoritative |

**Money movements at peak.** On an ordinary day the target moves 377,000 times: 100,000 transfers, 200,000 card or checkout
payments, 40,000 top-ups or withdrawals, 2,000 lending repayments, 20,000 BNPL purchases and 15,000 BNPL instalments. That is
10.5 a second at peak and 31 in a burst, against 55.6 reads a second at peak. The honest expectation stays the plan's: a
development machine's `kind` may carry the target's peak while a single node's CPU, not the architecture, is the first limit.
`P16-TSK-007` measures and names whichever it is.

### 3.2 The events each flow writes (W7, read from the code)

**Read** from every production call of `OutboxWriter.write` on each flow's happy path, and from the handler catalogue
(`docs/events/handler-catalogue.yaml`) for what a consumer writes in reaction. The event types are those of
`docs/events/event-catalogue.yaml`. Every posting writes one `ledger.JournalEntryPosted` (`ledger`'s `PostingEffect`). Every hold
placed or released writes `ledger.HoldPlaced` or `ledger.HoldReleased` (`HoldService`). A risk evaluation writes
`risk.RiskDecisionMade` in the caller's transaction (`RiskEvaluation`); its other events (`risk.LimitBreached`, an alert) come
only from a refusal or a pattern, never on the happy path. The completions (`transfers.TransferCompleted`,
`payments.PaymentCaptured`, `payments.PaymentExecuted`, `payments.WithdrawalCompleted`) are hints to risk's monitoring sweep and
record no signal (`RiskSignalIntake`), so they write nothing downstream.

| Flow, one happy-path occurrence | Events written (by producer) | Downstream | Events | Journal entries |
|---|---|---|---|---|
| Internal transfer | `risk` 1 (`RiskDecisionMade`), `ledger` 1, `transfers` 1 (`TransferCompleted`) | 0 | **3** | 1 |
| Card or checkout payment to a merchant (checkout by card) | `payments` 5 (`PaymentIntentCreated`, `RailSelected`, `PaymentAuthorized`, `PaymentCaptured`, and `PaymentClearedOnRail` when the clearing notice arrives), `risk` 1, `merchant` 1 (`FeeAssessed`, even at a zero fee), `ledger` 1 (the capture's one entry, the fee lines inside it), `checkout` 1 (`OrderPaid`) | 0 | **9** | 1 |
| Checkout paid from the wallet (book rail) | `payments` 3 (`PaymentIntentCreated`, `RailSelected`, `PaymentExecuted`), `risk` 1, `ledger` 3 (hold placed, hold released, the entry), `merchant` 1, `checkout` 1 | 0 | 9 | 1 |
| Wallet top-up by card | `payments` 5 (as the card payment), `ledger` 1 | 0 | **6** | 1 |
| Wallet top-up by bank push | `payments` 3 (`PaymentIntentCreated`, `RailSelected`, `PaymentExecuted`), `ledger` 1 | 0 | 4 | 1 |
| Wallet withdrawal | `ledger` 3 (hold placed, hold released, the entry), `risk` 1, `payments` 2 (`WithdrawalInitiated`, `WithdrawalCompleted`) | 0 | **6** | 1 |
| Lending instalment, billed and repaid | `lending` 2 (`InstalmentBilled`, `RepaymentReceived`), `ledger` 2 | 0 | **4** | 2 |
| BNPL purchase, pay-in-4 | `bnpl` 7 (`BnplApplicationSubmitted`, `BnplOffered`, `BnplEligibilityAssessed`, `BnplAgreementCreated`, `MerchantFinanced`, `InstalmentDue`, `InstalmentPaid`), `credit` 3 + k (`CreditDecisionRequested`, k × `CreditDataCollected`, `CreditAssessmentCreated`, `CreditDecisionRecorded`), `ledger` 3 (financing, billing, the first instalment), `checkout` 1 (`OrderPaid`) | `risk` 1 (`RiskSignalRecorded`, from `BnplApplicationSubmitted`) | **15 + k: 17 at k = 2** | 3 |
| BNPL scheduled instalment | `bnpl` 2 (`InstalmentDue`, `InstalmentPaid`), `ledger` 2 (billing, collection) | 0 | **4** | 2 |
| Credit decision (a loan's) | `credit` 3 + k | 0 | **5 at k = 2** | 0 |
| Sign-in | `identity` 1 (`AuthenticationSucceeded`) | 0 | **1** | 0 |
| A settlement file accepted and reconciled | `settlement` 1 (`SettlementBatchAccepted`), `reconciliation` 1 (`ReconciliationRunCompleted`) and one `SettlementExpectationSettled` per expectation a line settles | 0 | 2 + lines (R4) | the statement's recognition only, per accepted batch |

k is the number of data-source kinds the active credit policy reads, 0 to 2. The seeded lending policies read both, so k = 2.
No BNPL policy is seeded, so k = 2 is assumed for BNPL too. Where a flow branches, the table gives the common path: a final
instalment adds the closing event (`LoanClosed`, `BnplAgreementClosed`) and possibly a refund entry; a pay-in-N BNPL purchase
writes 11 + k and posts one daily accrual per obligation in a separate transaction; a risk `HOLD` writes the deferral or the
held withdrawal instead of the capture or the completion. A refusal writes fewer.

**The day, derived from W1–W8 and the counts above:**

| Source | Occurrences a day | Events each | Events a day | Entries a day |
|---|---|---|---|---|
| Internal transfers | 100,000 | 3 | 300,000 | 100,000 |
| Card or checkout payments | 200,000 | 9 | 1,800,000 | 200,000 |
| Top-ups (card) and withdrawals, R2 | 40,000 | 6 | 240,000 | 40,000 |
| Lending instalments, billed and repaid | 2,000 | 4 | 8,000 | 4,000 |
| BNPL purchases (pay-in-4, k = 2) | 20,000 | 17 | 340,000 | 60,000 |
| BNPL instalments | 15,000 | 4 | 60,000 | 30,000 |
| **Money movements** | **377,000** | **≈ 7.29** | **2,748,000** | **434,000** |
| Credit decisions (loans), R1 | 5,000 | 5 | 25,000 | 0 |
| Sign-ins | 240,000 | 1 | 240,000 | 0 |
| Settlement files and their lines, R4 | 80 files, 200,000 lines | 2 per file + 1 per line | 200,160 | at most 80 |
| **All** | | | **≈ 3,213,000** | **≈ 434,000** |

So W7 reads **≈ 7.3 events per money movement**, and the outbox carries about 3.2 million events a day, not the plan's 1.4
million: 37 a second on average, 89 at peak, 268 in a burst. The ledger takes about 434,000 entries a day, not 600,000: 5 a
second on average, 12 at peak, 36 in a burst.

### 3.3 What each consumer group reads

**Derived** from §3.2. One topic per producing module (`infra/kafka/topics.yaml`). A group reads every record of the topics its
handlers name in the handler catalogue, and applies the ones it handles (`PHASE_16_PLAN.md` §7). Records a day, by topic: `payments`
1,140,000 (card payments 1,000,000, card top-ups 100,000, withdrawals 40,000); `ledger` 474,000; `risk` 340,000; `identity` 240,000;
`checkout` 220,000; `merchant` 200,000; `reconciliation` 200,080; `bnpl` 170,000; `credit` 125,000; `transfers` 100,000; `lending`
4,000; `settlement` 80.

| Group | Topics it reads (handler catalogue) | Records read a day | At peak, a second |
|---|---|---|---|
| `finapp.risk` | `accounts`, `bnpl`, `crossborder`, `identity`, `lending`, `merchant`, `payments`, `transfers` | ≈ 1,854,000 (the account openings and cross-border volumes are outside W1–W8) | ≈ 51.5 |
| `finapp.bnpl` | `checkout`, `credit` | ≈ 345,000 | ≈ 9.6 |
| `finapp.lending` | `credit` | ≈ 125,000 | ≈ 3.5 |
| `finapp.kyc` | `party` | registrations only, outside W1–W8 | — |

At peak the busiest topic, `payments`, carries 1,140,000 ÷ 36,000 ≈ 32 records a second over 12 partitions. `P16-TSK-013`
measures the skew by key.

### 3.4 Resource cost per operation: measured, left for `P16-TSK-007`

**Measured.** Every cell is `—` until `P16-TSK-007`'s run fills it. A filled row names its workload (the engineering test
workload unless it says otherwise), the machine, the date, the commit and the artefacts. CPU, memory, GC, threads, pool and
database time are what `PHASE_16_PLAN.md` §4.3 and §5.2 say is measured, never stated in advance.

| Operation | Throughput carried | p50 | p95 | p99 | Error rate | CPU per operation | Memory | GC | Busy threads | Pool wait and hold | Database time per operation | Workload, machine, run |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Internal transfer | — | — | — | — | — | — | — | — | — | — | — | — |
| Card or checkout payment | — | — | — | — | — | — | — | — | — | — | — | — |
| Wallet top-up or withdrawal | — | — | — | — | — | — | — | — | — | — | — | — |
| Account or balance read | — | — | — | — | — | — | — | — | — | — | — | — |
| Sign-in | — | — | — | — | — | — | — | — | — | — | — | — |
| Provider callbacks | — | — | — | — | — | — | — | — | — | — | — | — |
| Merchant API | — | — | — | — | — | — | — | — | — | — | — | — |
| Settlement ingestion | — | — | — | — | — | — | — | — | — | — | — | — |
| Reconciliation | — | — | — | — | — | — | — | — | — | — | — | — |
| Credit decision | — | — | — | — | — | — | — | — | — | — | — | — |
| Lending repayment | — | — | — | — | — | — | — | — | — | — | — | — |
| BNPL purchase or instalment | — | — | — | — | — | — | — | — | — | — | — | — |
| Events written and consumed | — | — | — | — | — | — | — | — | — | — | — | — |
| Ledger journal entries | — | — | — | — | — | — | — | — | — | — | — | — |

**The first limit of the stress profile** (`PHASE_16_PLAN.md` §5.4, read in the fixed order generator, edge, application CPU,
threads, pool, database CPU, locks, I/O, broker): **—**, left for `P16-TSK-007`, with its evidence.

**The ratio of the production target to the engineering test workload**: **—**, left for `P16-TSK-007`.

---

## 4. The connection budget at ten

**Read** from the configuration on master `6981c167`. The startup guard `ConnectionPoolSizingGuard` reads the same keys, so the
fleet refuses to start when the arithmetic stops holding.

- **The rule** (`deploy/base/horizontalpodautoscaler.yaml`, `DISTRIBUTED_EXECUTION.md` §4a): the most instances that can hold a
  pool at once is `maxReplicas + 2 × maxSurge + 1`, which counts surge pods and terminating pods. That must not exceed
  `FINAPP_DB_INSTANCES`. Each instance holds the writer pool plus the reporting pool, and the fleet's demand must fit within the
  server's `max_connections` less the reserved connections.
- **The numbers**: writer pool 7, fixed size (`spring.datasource.hikari.maximum-pool-size`, `minimum-idle` the same); reporting
  pool 1 (`finapp.database.reporting.pool-size`); 12 reserved (`finapp.database.reserved-connections`); `maxSurge` 1
  (`deploy/base/deployment.yaml`).

| Where | `maxReplicas` | Instances (the rule) | `FINAPP_DB_INSTANCES` | Demand (instances × 8) | Server `max_connections` | Available (less 12) | Fits |
|---|---|---|---|---|---|---|---|
| Base and production | 7 | 7 + 2 + 1 = 10 | 10 | 80 | 100 | 88 | yes, 8 spare |
| Staging | 4 | 4 + 2 + 1 = 7 | 7 | 56 | 100 | 88 | yes, 32 spare |
| `kind` (test-only) | 3 | 3 + 2 + 1 = 6 | 6 | 48 | 60 | 48 | yes, exactly: the rehearsal measures the rule, not a margin |
| **Ten replicas** | 10 | 10 + 2 + 1 = 13 | would be 13 | **104** | 100 | 88 | **no: 16 short** |

**Ten replicas do not fit today's numbers.** The decision is owner decision C3's, and it is measured first by `P16-TSK-009`. For the
`kind` drill, the test-only server's `max_connections` is raised to at least 104 + 12 = **116** and the overlay's
`FINAPP_DB_INSTANCES` to 13. For staging and production, `P16-TSK-009` decides by measurement between a larger server, a smaller
pool, or a transaction-mode pooler. The pooler first needs the server-side prepared statements question answered
(`PHASE_15_PLAN.md` §12; only transaction-scoped advisory locks and settings exist, which a pooler preserves). Production's
`maxReplicas` stays 7 until then. The HPA has never been seen scaling, because `kind` has no metrics source (`P15-REM-007`):
`P16-TSK-009` installs one, test-only. The peak `pg_stat_activity` count for `application_name = 'finapp'` is measured at ten
during a rollout, as `P15-TSK-012` measured it at three.

---

## 5. Capacity and scaling strategy

From `PHASE_16_PLAN.md` §14. Each limit is measured before anything is changed, and a limit that comes from the architecture
rather than the infrastructure becomes an ADR before any change (ADR-0126 decision 5).

### 5.1 Per workload class

| Class | Strategy | Limit to measure | Note |
|---|---|---|---|
| Stateless APIs | Horizontal (HPA on CPU, calibrated by `P16-TSK-015`) | The connection budget (§4), then the primary | Adding replicas also adds schedule and consumer competitors |
| Payment orchestration | Horizontal; provider-bound | Provider latency × concurrency; the bulkhead | Never a retry of an ambiguous dispatch |
| Ledger operations | **Vertical** for the writer; hot rows by sub-accounts if measured (`PHASE_16_PLAN.md` §6.4) | Postings a second on the hottest row | No write scale-out without an ADR |
| Background workers (the 37 schedules of `DISTRIBUTED_EXECUTION.md` §3) | Leaderless on every replica, `SKIP LOCKED` claims; batch sizes | Claim contention at ten; batch throughput per tick | Phase 15's C5 left a role split (`api`/`worker`) to this measurement (`P16-TSK-015`) |
| Kafka consumers | Horizontal to the partition count (12) | Lag per group (§3.3) | More partitions means a new topic, never a resize in place (`PHASE_16_PLAN.md` §7) |
| Reconciliation workers | Chunked, resumable, any instance | Chunks a minute; per-source serialisation | |
| Report generation | Its own pool (`finapp_reporting`), read-only | The primary's read load; a replica only for this pool (`PHASE_16_PLAN.md` §6.6) | |
| Risk evaluation | Synchronous, a 250 ms budget with a recorded fail-safe | The fail-safe rate at load (a fail-safe is a decision, not an error) | Risk's whole-day proofs measured here |
| Loan servicing | Leaderless sweeps; the month-start spike (R3) | The spike's duration | |
| Notifications | **None exist** (no notifier: `DECISIONS.md` §Deferred, "changing the verified contact channel") | — | N/A |
| Webhooks (inbound callbacks) | Horizontal; evidence and dedupe first, inquiries bulkheaded | The callback rate at burst | |

### 5.2 The distinctions

- **Horizontal** for the stateless request path and the claim-based workers.
- **Vertical** for the PostgreSQL primary.
- **Partitioning** of Kafka topics (fixed at 12) and, if `P16-TSK-012` decides it by an ADR, of high-volume tables.
- **Workload isolation** by pools and bulkheads, and by a role split only if measured (`P16-TSK-015`).
- **Read scaling** only for the reporting pool (`PHASE_16_PLAN.md` §6.6).
- **Write limitations**, stated: writes do not scale out. One primary holds the journal, and every arbiter is a row or a
  constraint on it.
- **Backpressure** by the outbox and by explicit shedding (`P16-TSK-016`).

### 5.3 Bottlenecks that need architecture, not infrastructure

These are candidates, each confirmed or struck by measurement:

- the per-currency hot rows (`PHASE_16_PLAN.md` §6.4);
- `settlement.source FOR UPDATE` held for a whole acceptance;
- risk's whole-day proofs, and the rescreening census counting each version whole;
- the single writer itself, at a volume far above the production target.

Each one confirmed becomes an ADR before any change.

---

## 6. Keeping this model true

- `P16-TSK-007` fills §3.4 and names the first limit. A later run replaces a cell only with its own named run.
- A change to W1–W8 is the owner's. It changes the derived cells that cite it, by a dated note.
- A change to the code that adds or removes an event on a flow changes §3.2. The counts were read on master `6981c167`.
- A change to the pool, the replicas, the surge or the server changes §4. `ConnectionPoolSizingGuard` holds the live arithmetic,
  and this page holds its reading.
