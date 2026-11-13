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
  *(2026-11-13, `P16-TSK-007`: §3.4 holds the measured cells, each naming its run; an operation no `kind` deployment can run keeps `—`
  with its reason, and the runs not made are owed under `POSTPONED_ISSUES.md` PI-025.)*

**The reference machine's two clocks** (`OPERATIONS_RUNBOOK.md` §11.45). Its Linux VM's monotonic clock can run faster than its wall
clock, which follows the host's: 1.083 times in E30's run on the morning of 2026-11-13, 1.005 times in the isolation run that afternoon.
The load generator schedules arrivals and times requests on the monotonic clock, so a rate written in a profile is carried that much
faster in the host's seconds, and a latency reads that much longer than the host would measure it. A measured rate here is a host
second's; a latency is the client's, as k6 counted it. Each run records its own ratio (`load/clock.tsv`, `load/workload.txt`).

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
- **R6 (W1, W3)** *(2026-11-13, the calibration of §4a)*: at most **1,000** of the platform's active customers share one public IPv4
  address at once (a carrier's NAT pool address, a campus, an office). W1–W8 say nothing of addresses, and the per-source limits are
  counted per address, so their numbers need one. It is a deliberately high reading: 0.5% of a day's 200,000 active customers behind one
  address. An IPv6 client is its own /64. It is the owner's to change, as R2–R4 are; `PerSourceLimitsAreCalibratedTest` reads this
  number, so a change re-checks every limit that rests on it.

**The owner's decision (2026-11-13, "accept recommendations, start wave B"):** W7 as read from the code's event writers (§3.2: about 7.3 events per money
movement, about 3.2 million a day) replaces the plan's estimate of 4; R1 stands (W6's 5,000 are loan decisions, each BNPL
purchase carrying its own); R5 stands (merchant payouts out of the model until W4 states a frequency). R2–R4 remain readings the
owner may change.

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

*(2026-11-13, `P16-TSK-007`: on `kind` the credit flows of this table cannot run. Every credit policy carries an exposure rule that
reads the bureau's total balance, and no `kind` deployment can configure a bureau (the application refuses one until `CURRENT_STATE.md`'s
unresolved question 13 is answered), so a decision there takes its fallback (`INV-CRD-10`) and a person's approval of a referral is
refused (`INV-CRD-09`): no BNPL agreement and no loan exists for a purchase, an instalment or a repayment. The load profiles drive the
other flows, 90.2% of the target's money movements; what the measured runs wrote per flow is in each run's
`written-events-by-type.tsv`, and §3.4 reads the events and entries a second from them.)*

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
measures the skew by key. *(2026-11-13: built as E47's records per partition per topic over the load, `load/skew.tsv`, and not yet run:
owed under `POSTPONED_ISSUES.md` PI-025. Read from the code meanwhile: every record's key is its aggregate's id (`PendingEvent.partitionKey`).
Most aggregates are minted per movement - a payment, a transfer, a journal entry - and a key carries one movement's few records. One
is not: the merchant's fee events (`merchant.FeeAssessed`, `merchant.FeeReturned`, `MerchantSettlement`) are keyed by the merchant, so
`finapp.merchant` carries every fee of a merchant on one partition, and the relay drains them as one aggregate, in order, one instance
at a time. Over W4's 2,000 merchants that spreads; on the rehearsal's one merchant every fee lands on one partition. E47 reads how much.)*

### 3.4 Resource cost per operation: measured (`P16-TSK-007`)

**Measured** by two runs on the reference machine (a Windows host, Docker Desktop's WSL2 VM with 12 cores and 15.6 GiB, one `kind`
node; each replica limited to 2 cores and 1 GiB, its heap 75% of it, PostgreSQL to 1 core and 1 GiB, the broker to 1 core and 1 GiB:
`deploy/overlays/kind`; the images built from the branch's tree, whose application code and migrations are master `0a09a273`'s):

- **E30 at three replicas**, the stress profile, 2026-11-13 09:14–09:32 UTC, commit `89b8fc60`, artefacts
  `build/rehearsal/experiments/E30/r3`. Its step 3 is the highest it carried within every objective: 0.39 of the target's peak as
  written, 0.42 in the host's seconds (its clock ratio 1.083), held 277 s. A sample stopped it in step 4 (below).
- **The isolation profile at three replicas**, 2026-11-13 11:36–12:07 UTC, commit `f021383f`, artefacts
  `build/rehearsal/experiments/isolation/r3`: four minutes with no load, then each operation alone at the target's peak for four
  minutes (its clock ratio 1.005). Green: 57 samples, every one `PASS`; every movement resolved by its key; the census green.

A throughput or latency outside brackets is the operation's in E30's step 3, where the whole mix ran; in brackets, alone at the target's
peak (isolation). Every cost is from the isolation run: the operation's stage less the idle stage, divided by its arrivals a host
second. CPU is milliseconds of a core per operation: the replicas', the database's and the edge's. Memory is the three replicas' cgroups
together, against 3 GiB of limits, and their heap. GC is the replicas' collection time per operation. The pools are the fleet's 21
writer connections: active mean and most, and the longest wait. Database time is the statements' execution time per operation, with
the statements and commits it took.

| Operation | Throughput carried | p50 (ms) | p95 (ms) | p99 (ms) | Error rate (`5xx`) | CPU per operation (ms) | Memory | GC | Busy threads | Pool wait and hold | Database time per operation | Workload, machine, run |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Internal transfer | 1.18 a second (2.76, the target's peak 2.78) | 48 (38) | 184 (58) | 347 (87) | 0 of 341 attempts (0 of 691) | replicas 29, database 26, edge 5 | 1,686 MiB; heap 508 MB | 2.4 ms | not exported | 2.0 active (3 at most), wait ≤ 23 ms, none pending | 6.8 ms; 138 statements, 8.8 commits | E30 step 3; isolation stage 5 |
| Card or checkout payment | 2.35 a second (5.49, peak 5.56) | 97 (99) | 349 (444) | 601 (1,348) | 0 of 1,362 (0 of 2,750) | replicas 86, database 74, edge 21, broker 18, simulator 5 (its two callbacks inside) | 1,707 MiB; heap 513 MB | 0.4 ms | not exported | 4.5 (14), wait ≤ 119 ms, none pending | 36 ms; 246 statements, 24 commits | E30 step 3; isolation stage 7 |
| Wallet top-up or withdrawal (top-ups by card) | 0.47 a second (1.10, peak 1.11) | 81 (63) | 308 (123) | 569 (175) | 0 of 401 (0 of 815) | replicas 76, database 50, edge 23, broker 15, simulator 8 (the stage's settlement file inside) | 1,695 MiB; heap 507 MB | under 0.1 ms | not exported | 2.1 (3), wait ≤ 23 ms, none pending | 13 ms; 186 statements, 26 commits | E30 step 3; isolation stage 6 |
| Account or balance read | 23.5 a second (55.4, peak 55.6) | 11 (11) | 67 (23) | 137 (37) | 0 of 6,494 (0 of 13,232) | replicas 3.7, database 1.5, edge 3.7 | 1,522 MiB; heap 435 MB | under 0.1 ms | not exported | 2.4 (5), wait ≤ 4 ms, none pending | 0.3 ms; 9 statements, 2 commits | E30 step 3; isolation stage 3 |
| Sign-in | 2.81 a second (6.63, peak 6.67) | 65 (58) | 208 (207) | 296 (254) | 0 of 779 (0 of 1,582) | replicas 68 (the credential hash), database 4, edge 4 | 1,636 MiB; heap 440 MB | 12 ms, 0.36 pauses | not exported | 2.3 (7), wait ≤ 5 ms, none pending | 0.9 ms; 15 statements, 3 commits | E30 step 3; isolation stage 4 |
| Provider callbacks | two per card capture: 3,349 deliveries over the isolation run, 157 of them redeliveries, about 11 a second in its checkout stage | 20 (the sender's) | 113 | 278 | 0 not accepted of 3,349 | inside the card payment's and the top-up's | — | — | not exported | — | — | isolation, `load/callbacks-load-*.tsv` |
| Merchant API | 4.69 a second (11.05, peak 11.1) | 47 (6) | 474 (13) | 753 (21) | 0 of 1,298 (0 of 2,639) | replicas 6, database 0.9, edge 4.6 (alone, while the merchant's payable history was short) | 1,496 MiB; heap 444 MB | under 0.1 ms | not exported | 2.1 (3), wait ≤ 2 ms, none pending | 0.2 ms alone; the payable read's mean 20 ms at E30's step 1, 74 ms at step 2, 255 ms at step 3 (below) | E30 step 3; isolation stage 2 |
| Settlement ingestion | one PSP file of 4 captures (8 lines with their fees), accepted 5 s after it arrived | — | — | — | the file taken whole, every line an item | not separable (inside the top-up stage) | — | — | — | — | — | isolation, `load/written-settlement.tsv`, `load/settlement.log` |
| Reconciliation | that file's batch completed 6 s after the file arrived, every capture allocated to its expectation | — | — | — | none abandoned | not separable | — | — | — | — | — | isolation, as above |
| Credit decision | — | — | — | — | — | — | — | — | — | — | — | not driven: no `kind` deployment can configure a bureau (unresolved question 13), so no decision there is the target's (`INV-CRD-10`); no simulated bureau is built for `kind` in Phase 16 (the owner's decision of 2026-11-13 on that question) |
| Lending repayment | — | — | — | — | — | — | — | — | — | — | — | not driven: no loan can be approved on `kind` (`INV-CRD-09`, `INV-CRD-10`), as above |
| BNPL purchase or instalment | — | — | — | — | — | — | — | — | — | — | — | not driven: no BNPL agreement can be approved on `kind`, as above |
| Events written and consumed | 27.3 written a second, published 0.6 to 1.0 s after their write on average, 4.0 s at most; 44.4 a second alone in the checkout stage, published 6 s after their write in its first minute and 59 s in its last (the relay behind, below) | — | — | — | no dead letter | — | — | — | — | — | — | E30 step 3 and isolation stage 7, `load/written-events-per-minute.tsv` |
| Ledger journal entries | 3.97 a second, 12.6 lines (5.55 and 22.2 alone in the checkout stage) | — | — | — | 0 refused | inside each movement's | — | — | — | — | — | E30 step 3 and isolation stage 7, `load/written-entries-per-minute.tsv` |

Busy request threads are not exported: `tomcat_threads_busy_threads` has no series, because Tomcat's MBean registry is off. The live
threads stayed between 310 and 327 through the isolation run, 325 in E30's first three steps. Memory and heap grew by stage, not by
operation, so each figure is the fleet's at that stage, and never above 57% of the replicas' limits. The settlement file and its
reconciliation were too little work to separate from the top-ups' stage. The posting's share of a movement's latency (§3.1's last row)
was not separated from its command's.

**The outbox relay behind at the target's checkout peak** (isolation stage 7, `load/written-events-per-minute.tsv`,
`load/stages-resources.tsv`). Checkouts alone at the target's peak wrote 44.4 events a second, and the delay from write to publication
climbed from 6 s to 59 s over the stage's four minutes (the outbox's oldest pending row 48 s at its last sample) while no resource was
near its limit (the database at 0.46 cores, the broker at 0.13). Each replica's relay drains at most 8 aggregates a poll, one poll a second
after the last (`OutboxRelay`'s default, `finapp.outbox.poll-interval` `PT1S`), and the three relays read the same oldest heads and
compete for them (read from the code, not measured), so the fleet's drain is bounded by aggregates, not by events or CPU.
`event_freshness` (published within 60 s) would be breached within minutes of the target's checkout peak, and the target writes twice
this (§3.1: 89.3 a second). Routed to `P16-TSK-013`.

*(2026-11-13, `P16-TSK-013`, ADR-0136: changed, not yet measured. A candidate another relay drained after the candidates were read now
takes no slot of the batch, and a cycle that worked its whole batch and failed nothing is followed at once by another, a short or failed
one waiting the interval as before; the lock, the order and the conditional mark are unchanged. The relay's ceiling in events and
aggregates a second at three and seven replicas, and whether more relays add drain, are E47's (`OPERATIONS_RUNBOOK.md` §11.51), owed under
`POSTPONED_ISSUES.md` PI-025: no figure above is replaced, and none is claimed for the change.)*

**The first limit of the stress profile** (`PHASE_16_PLAN.md` §5.4, read in the fixed order generator, edge, application CPU,
threads, pool, database CPU, locks, I/O, broker): **the database's CPU**, at three replicas. E30's run (2026-11-13 09:14–09:32 UTC,
commit `89b8fc60`, artefacts `build/rehearsal/experiments/E30/r3`: `load/k6/stages.tsv`, `load/stages-resources.tsv`,
`load/pg-statements.tsv`, `load/fold-explain.txt`) carried its first three steps within every latency objective with no `5xx`, and broke
at its fourth, 0.49 of the target's peak as written (0.53 in the host's seconds): each operation's p99 between 7.9 and 22.0 s, `5xx`
on every one (611 of the checkouts' 2,990 attempts), the edge's in-flight bound answering `429`. Its 35th sample could not read a
correctness gauge and stopped the load there, the stage's 277 s complete. Read in the order, steps 1 to 4:

| Resource | Step 1 | Step 2 | Step 3 | Step 4 | Reading |
|---|---|---|---|---|---|
| The generator | 0 dropped | 0 dropped | 0 dropped | 0 dropped | Its busiest container at 31% of its one core; the callback senders at most 6% |
| The edge | 0.13 cores | 0.15 | 0.19 | 0.26 | Never held by its limit; its `429`s at step 4 are requests piling up behind slow replicas (the per-source in-flight bound) |
| The replicas' CPU | 0.52 cores of 6 | 0.50 | 0.52 | 0.60 | Three replicas at 2 cores each, held back in under 0.1% of their CPU periods |
| Their threads | 325 live | 324 | 326 | 559 (656 at most) | Climbing only at step 4, requests waiting; busy request threads are not exported |
| The pools | 2.7 active (6 at most) | 3.3 (8) | 5.0 (9) | 16.3 (21: all) | At step 4 every connection held, up to 313 waiting, the wait at its 5 s bound: held by statements the database ran slowly |
| **The database's CPU** | 0.23 cores of 1 | 0.35 | 0.60 | **1.10** | Held back by its limit in 1%, 5%, 34% and **100%** of its CPU periods; its statements' time 0.08, 0.20, 0.70 and 8.4 s a second |
| Its locks | 0 waiting | 0 | 0 | 1.2 (4 at most) | No deadlock |
| Its I/O | 0.4 waiting on I/O, 0.3 on WAL | 0.3, 0 | 0.4, 0.3 | 1.0, 8.0 | The WAL waits at step 4 come with a CPU throttled in every period: the WAL's own writes wait for the CPU |
| The broker | 0.08 cores | 0.08 | 0.07 | 0.04 | Events published within 1 to 4 s of their write at steps 1–3, then 88 to 147 s once the database stalled |

Where the database's CPU went (`pg-statements.tsv`, one statement by `queryid`): the merchant API's payable read, which folds the
merchant payable account's whole history on every call (`MerchantPayable` through `JdbcPositionBreakdown`; 4,039 lines and 41.9 ms
idle after the run, `fold-explain.txt`), took 39%, 69%, 86% and 81% of all statement time at steps 1–4, its mean 20, 74, 255 and
2,658 ms. The rig has one merchant receiving every checkout, where W4 spreads them over 2,000, so the rig magnifies it; the read still
grows with the account's history on any merchant, which is §5.3's whole-history read, routed to `P16-TSK-008`. The run also found two
defects, routed to `P16-TSK-016`: twelve checkouts at step 4 whose creating answer was a `500` after their session committed
(`CheckoutService.create` reads the session back in a second transaction, which the pool's 5 s bound refused), and the source-join
census starved of the pool, which is what stopped the sampler.

The isolation run says the limit is the mix's, not any one operation's: each operation alone at the target's peak was carried within its
objective with no `5xx`, the database at most 0.46 cores (checkouts), and the merchant API alone cost 0.9 ms of the database's CPU a
read while the merchant's history was short. In the mix every checkout lengthens the history every merchant read folds, so the read's
cost climbs with the run's age as well as its rate.

**Not measured**, owed under `POSTPONED_ISSUES.md` PI-025 (the owner, 2026-11-13, postponing Phase 16's `kind` runs until after the
project's phases): the stress profile at seven replicas (E30), so the per-replica saturation at seven against three is not compared; the
normal, peak and sustained profiles at three and seven (E29), so nothing here says how long the step-3 mix holds; the planted slow
statement (E31). The first limit is named at three replicas only.

**The ratio of the production target to the engineering test workload: about 2.4**, and 2.6 counting the money movements no `kind`
deployment can drive. The engineering workload is E30's step 3, every driven operation at 0.42 of the target's peak in the host's
seconds (0.39 as written, times its clock ratio 1.083), so the target is 1 / 0.42 ≈ 2.4 times it; its money movements were 3.99 a
second against the target's 10.5 (§3.1), 2.6 times. The step held 277 s; no longer hold was run (E29, owed above), and because the
merchant read's cost grows with the history, a longer hold may carry less.

### 3.5 Contention: the hot rows, locks, deadlocks and plans (`P16-TSK-008`)

**Nothing in this section is measured.** The runs that measure it are built and owed under `POSTPONED_ISSUES.md` PI-025 (the owner,
2026-11-13): E38 (the concurrent-financial profile, at seven and three replicas), E39 (the soak, with its plans and its index build) and
E40 (the sweeps' failure storm), `RESILIENCE_EXPERIMENTS.md` and `OPERATIONS_RUNBOOK.md` §11.48. Every cell marked measured reads `—`
until a run fills it. The readings are **read** from the code, or **derived** from W1–W8, and each says which. ADR-0133 records what is
decided before the runs and what waits on them.

**The contended rows** (`PHASE_16_PLAN.md` §6.4), each with what takes it, how long it is held, and the run that measures it:

| Row | Taken by | Held for (read) | Measured by | Waits: p50 / p95 / longest; share of the command | Throughput at which the wait passes the objective |
|---|---|---|---|---|---|
| A customer wallet's account row (`ledger.ledger_account FOR UPDATE`) and its balance row | Transfers (both accounts, one fixed order), top-ups, withdrawals, refunds to a wallet | The command's transaction, which **derives the source's balance from every line of its account under the lock** (`AvailableBalance.underLock`): the hold grows with the account's history | E38 (the pair) | — | — |
| A merchant's payable balance row | Every capture and refund of that merchant (the projection's upsert in the posting's transaction) | The posting's remainder of its transaction; E30 saw a mean upsert of 14 ms for the one merchant | E38 (the one merchant) | — | — |
| `FEE_REVENUE` per currency | Every capture with a fee | As above | E38 | — | — |
| `SETTLEMENT_CLEARING` per currency (and each counterparty-keyed clearing) | Every card capture and top-up | As above | E38 | — | — |
| `FX_POSITION`, `FX_SPREAD_REVENUE` per currency | Every conversion | As above; Phase 9's storm measured the conversion door's p99 at 713–760 ms (ADR-0076 §9) | Not driven on `kind` (no load profile drives a conversion) | — | — |
| `LENDING_CAPITAL` per currency (L6) and `BNPL_MERCHANT_FEE_INCOME` | Every loan acceptance and draw, every BNPL purchase and principal-reinstating reversal, across their (P) and L8 waits | From the capital check to the commit | Not drivable on `kind`: no credit approval can be given there (§3.4); Phase 12's storm: 65 waits, p95 142 ms, max 1,070 ms (two instances) | — | — |
| `settlement.source FOR UPDATE` | An acceptance, and every upload, pull and parse of the same source | A whole acceptance | E38's one mid-run file; at W5 (four files a day per source, **derived**) one acceptance per source every six hours | — | — |
| The idempotency claim row (`platform.idempotency_record`) | Every keyed command; a lease takeover meets the original's last transaction (#52) | Its two transactions | E38 (any `40P01` attributed); a slow flight is `P16-TSK-018`'s E21 | — | — |
| Advisory namespace 3 (`hashtext(attempt_id)`) | Refunds and dispute answers per attempt (#53) | The transaction | E38's sampler names any wait on it (`advisory:3:<key>`) | — | — |
| The abuse window counter (`platform.window_counter`, unlogged) | Each request at an abuse limit's door, one row per source bucket and window (a refused count reads only) | One conditional upsert | E38, E39 | — | — |
| The posting cutoff's namespace 14, shared | Every journal insert, against a cutoff move | The posting's transaction | No move during a load: none expected | — | — |

**Deadlocks and serialisation failures.** Read: every multi-account path takes its rows in one order (the transfer's fixed order, the
projection's sorted apply, `lockBalancesInOrder` for the multi-entry callers), and money moves under `READ COMMITTED`, so none is expected
on what the load drives. E38 counts them against the server's own counter, and each record is explained in §11.48's record. Measured: —.

**The whole-history folds** (ADR-0133). Read: the merchant API's payable read and every balance decision fold an account's whole history.
The first does it on every call; the second does it under the account's row lock. **Derived** at W4 for the payable read: about 2 ms of a
core per read per day of a merchant's history, so about 60 ms a read after a month and 0.73 s after a year, against 5.56 payable reads a
second in the busiest hour. The read was **measured** as E30's first limit (§3.4). Its bound is ADR-0133 decision 3, and its build
was placed by the owner on 2026-11-13 (`THREAT_MODEL.md` §6 item 55 (i)): `P16-REM-001`, its database cases run before it merges.
*(Read "proposed, and its build is the owner's to place" until the owner's decision of 2026-11-13.)* The decision path's fold is
measured by E38's pair.

**The sweeps' failure records in a storm** (routed by `P16-TSK-017`). Read: each of the eight listings' `NOT EXISTS` probe is one primary-key
probe of `(leg, row_id)` per candidate the listing reads. With the tables empty it adds that probe and no more. In a storm that backs off
every candidate, a listing's `LIMIT` never stops early, so each tick of each instance reads every candidate, the card legs with their
lateral age read too. The cost is the candidate count, not the batch. The record table takes at most one write per row per backoff window
(1 minute doubling to 10), and its updates change no indexed column, so they can be heap-only. E40 measures the plans, the latency, the
write rate and the bloat. Measured: —.

**The transactional index build** (`THREAT_MODEL.md` §6 item 42 (b)). Read: a plain `CREATE INDEX` holds `SHARE` on its table for the whole
build, so every insert into the journal, and with it every posting, waits that long. The migrator's `lock_timeout` bounds only the wait for
the lock. E39 measures the stop on the soak's journal, and the build rate extrapolates it to the target's. Measured: —.

**The posting scope's uniqueness on the hot path** (`P16-TSK-011`, ADR-0141; routed here by this section's task). Read: each posting
adds one probe of `journal_entry_one_per_idempotency_scope` (the lookup a fresh claim makes) and one entry in it (the conditional insert);
each claim's completion one entry in `idempotency_record_sweepable`, partial on the terminal states; no row lock and no new hot row.
**Derived**: about 434,000 entries a day (§3.1), so the index grows by that many entries a day and the idempotency sweep keeps the record
table at about 31 days of keyed commands. E62 measures the posting's cost against E38's run on the tree before the uniqueness, and E64 the
census and both builds' write stop on the soak's tables (each build holds `SHARE` on its table for its length, as above). Measured: —.

**The plans and the soak's growth** (`PHASE_16_PLAN.md` §6.5, §6.7). E39 writes the top statements' plans, every sequential scan, the named
tables' growth and dead rows, autovacuum's runs, the checkpoints and the WAL a money movement. Measured: —.

### 3.6 Growth, partitioning and read replicas (`P16-TSK-012`)

**Nothing in this section is measured by its task.** One ratio is §3.4's measured run's (the journal's lines an entry), and says so. Rows are
**derived** from W1–W8 and §3.1–§3.3, or **read** from the schema and the code, each
naming its source. Bytes are not derived: E65, which rides the soak (E39), reads every named table's growth per hour and per money
movement from the soak's own samples (`load/growth.tsv`), owed under `POSTPONED_ISSUES.md` PI-025. ADR-0142 records the decisions taken
on these figures, and what would overturn each.

**Growth a day at the target, by table.** A figure per movement from E65, multiplied by the target's 377,000 money movements a day (§3.1),
gives the measured counterpart of each row's growth.

| Table | Rows a day at the target | From | Kept | Standing at the target | Sweep |
|---|---|---|---|---|---|
| `ledger.journal_entry` | ≈ 434,000 | §3.2 | for ever (`INV-HIST-01`) | ≈ 158 million a year, ≈ 475 million in three | none, ever |
| `ledger.journal_line` | ≈ 1.4 million | §3.2's entries × 3.17 lines an entry, as E30's step 3 wrote them (12.6 lines over 3.97 entries a second, §3.4, measured on `kind`); a transfer writes 2, a card capture with its fee 4 | for ever | ≈ 0.5 billion a year, ≈ 1.5 billion in three | none, ever |
| `platform.outbox_event` | ≈ 3,213,000 | §3.2 | 30 days after publication (`finapp.outbox.retention`), its dead-lettered, discarded and parked-referenced rows kept as evidence | ≈ 96 million | at most 20 batches of 500 a 5-minute tick on each instance (`RetentionSweepSchedule.MAX_BATCHES`, `finapp.retention.sweep.batch-size`, `.interval`): 2.88 million a day an instance, 8.64 million at the three-replica minimum, 2.7 times the target's writes |
| `platform.inbox_message` | at most ≈ 2,324,000 | §3.3: one row a record a group's handler applies, of the records the three groups read | 14 days (`finapp.inbox.retention`) | at most ≈ 32.5 million | the same capacity, its own schedule |
| `platform.idempotency_record` | at least 377,000 | one claim a keyed money movement, operator commands beside | until its sweep exists (`P16-TSK-011`) | ≈ 138 million a year until then | none yet |
| `platform.audit_record` | ≈ 0.6 to 1.4 million | read from the happy paths' writers: a sign-in 1 (240,000), a transfer 1 (100,000), a top-up or withdrawal 1 (40,000), a card payment 1 to 5 (creation, confirmation, capture and each concluded outcome; 200,000) | kept; archived and kept (`P16-TSK-010`) until a jurisdiction's period (`THREAT_MODEL.md` §6 item 23 (b)) | ≈ 0.2 to 0.5 billion a year | none (archival keeps the rows) |
| `platform.audit_archive_pending` (ADR-0135) | as many as the audit trail | one queue row a new audit row, by trigger (`V015`); routed by `P16-TSK-010` | until its row's segment is archived; while no environment names a store, for ever | grows with the audit trail until a store is named, then a segment's worth | the archive's own claim, by segment |
| `identity.session` | ≈ 240,000 | one session a sign-in (W3) | its idle bound plus the sweep's grace (`finapp.identity.session.sweep.grace`, 30 days) | ≈ 7 million | the session sweep, a fourth `RetentionSweepSchedule` (`P16-TSK-010`) |
| `payments.payment_attempt` and its history | ≈ 220,000 attempts | 200,000 card or checkout payments and 20,000 card top-ups (R2); a history row a transition | kept | ≈ 80 million attempts a year | none |
| `ledger.account_balance`, `platform.window_counter` | bounded by accounts, and by sources × windows | — | in place; the counter unlogged and swept each minute | by population, not by time | the counter's own |

**The queries a partition key would serve** (read; ADR-0142's Context):

| Table | Read by | A time key | A hash of the account |
|---|---|---|---|
| journal lines | an account over its whole history: `JdbcBalanceDerivation` (the available balance, under the account's lock), `JdbcPositionBreakdown` (the merchant payable), `JdbcPurposeBalances`, `ProjectionVerification`, `JdbcProjectionRebuildRows`, the credit exposure reads | prunes nothing: every partition probed | prunes to one |
| journal lines | an account over a date range: `JdbcStatementDerivation` | prunes, once the entry's date is on the line | prunes to one |
| journal lines | the ledger over a month: the GL (`LedgerJournalReads`, `LedgerTrialBalanceReads.WITHIN`), `Phase9Reports` | prunes, once the date is on the line | prunes nothing |
| journal lines | from inception to a date: `LedgerTrialBalanceReads.BEFORE` and `LEDGER`, `LedgerCounterpartyPositionReads`, `LendingFundedAsOfCutoffReads`, `ProductReconciliationDisclosure`, `JdbcBnplReportReads`, `LendingReports` | every partition before the date | prunes nothing |
| journal lines | by entry: the balance trigger on every insert (`journal_entry_balances`), the census walk, the shapes | crosses partitions unless the entry's date is known | crosses partitions |
| journal lines | whole: `TrialBalance`, the proofs | whole either way | whole either way |
| outbox, inbox | the relay's pending heads by aggregate; a claim or a dedupe by key; the sweep by age | serves the sweep alone, and splits the key | — |
| idempotency records | a claim by `(scope, idempotency_key)` | splits the arbiter | — |

**Read replicas.** Read: both pools connect to one address (`DatabaseEndpoint.url`): the writer's, and report generation's
(`ReportingPool`, as `finapp_reporting`), so no read reaches a copy today. Report generation is the only read ADR-0142 leaves eligible,
under its conditions. Its trial-balance-based definitions fold the journal from inception (the trial balance's opening and ledger windows,
the balance sheet, the GL activity's and the purpose reports' openings), so one statement's cost grows with the journal's lines. W1–W8
state no report runs a day, so report generation's share of the primary is not derived. **Derived**: one statement reaches the four-minute
generation bound (`finapp.accounting.report.generation-timeout`) once the journal's lines exceed 240 s × its fold rate. At the target's
≈ 0.5 billion lines a year, a rate below ≈ 2.1 million lines a second passes it within the first year, and below ≈ 6.3 million within
three. E65 fits that rate from seven runs over the soak's growing journal (`load/reports-reading.txt`): a statement's milliseconds a million
lines, and the lines at the bound. Measured: —. The bound is `THREAT_MODEL.md` §6 item 64, placed by the owner on 2026-11-13 at (i):
an opening checkpoint for each locked month, built by `P16-REM-003`. *(Read "the owner's to place" until that decision.)*

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
| `kind` (test-only) | 10 | 10 + 2 + 1 = 13 | 13 | 104 | 116 | 104 | yes, exactly: owner decision C3's larger test-only server; a rehearsal run at three sets its own copy to 6, 48 and 60 *(read "3, 6, 6, 48, 60, 48" until 2026-11-13)* |
| **Ten replicas on production's server** | 10 | 10 + 2 + 1 = 13 | would be 13 | **104** | 100 | 88 | **no: 16 short** |

**Ten replicas do not fit today's numbers.** The decision is owner decision C3's, and it is measured first by `P16-TSK-009`. For the
`kind` drill, the test-only server's `max_connections` is raised to at least 104 + 12 = **116** and the overlay's
`FINAPP_DB_INSTANCES` to 13. For staging and production, `P16-TSK-009` decides by measurement between a larger server, a smaller
pool, or a transaction-mode pooler. The pooler first needs the server-side prepared statements question answered
(`PHASE_15_PLAN.md` §12; only transaction-scoped advisory locks and settings exist, which a pooler preserves). Production's
`maxReplicas` stays 7 until then. The HPA has never been seen scaling, because `kind` has no metrics source (`P15-REM-007`):
`P16-TSK-009` installs one, test-only. The peak `pg_stat_activity` count for `application_name = 'finapp'` is measured at ten
during a rollout, as `P15-TSK-012` measured it at three.

*(2026-11-13, `P16-TSK-009`, ADR-0134: **decided** from §3.4's measurement at three replicas.)* At the engineering workload the
fleet's 21 writer connections were 5.0 active on average and 9 at most, with no request pending; at the break every connection was
held because the database's CPU was held back in every period. The pool's size is therefore not the binding limit, and a larger fleet
of pools would only queue more work inside a CPU-bound database. So:
- **`kind`**: a larger test-only server, the table's row above, which admits ten replicas within an exact budget. Each rehearsal
  run holds its fleet at its own count in its own copy of the overlay with the formula at that count (`kind-rehearsal.sh`'s
  `fleet_patch`).
- **Staging**: unchanged.
- **Production**: `maxReplicas` stays 7. Ten replicas would take a larger declared server, `max_connections` 116 in the overlay's
  one literal that Terraform reads, once the ten-instance runs are made and the HPA calibrated (`THREAT_MODEL.md` §6 item 56,
  decided so by the owner on 2026-11-13).
  *Derived, not measured*: at the target's peak (about 2.4 times the engineering workload, §3.4) the replicas would use about
  0.52 × 2.4 ≈ 1.25 cores, which the HPA's target of 70% of 500m a pod answers with about four replicas. Seven is not the limit the
  target meets first; the single writer's CPU is.
- **No smaller pool**: the correctness monitors already starved of the pool at the break (routed to `P16-TSK-016`).
- **No transaction-mode pooler yet**: the driver sets isolation per session, outside the transaction (ADR-0134's context).

The HPA's metric on `kind` is metrics-server, installed by the `load` scope (`OPERATIONS_RUNBOOK.md` §11.49). **Not measured**, owed
under `POSTPONED_ISSUES.md` PI-025: each replica's pools at the engineering workload at ten replicas, the peak there, an operator
admitted at it and with the fleet held at 104 (E41); the peak during a rollout at ten (E26, `P16-TSK-014`). *(2026-11-13,
`P16-TSK-014`: E26 built, beside the autoscaler's own scale from three to ten and back under load (E70) and the sustained profile at
ten (E69), every one owed under PI-025; `OPERATIONS_RUNBOOK.md` §11.58. The autoscaler's target of 70% would not act at the engineering
workload (derived from §3.4: the replicas near a third of their request there), so E70 moves the metric with a surge of sign-ins; its calibration
is `P16-TSK-015`'s.)* *(2026-11-13: calibrated, §4a.)*

---

## 4a. The calibration: the autoscaler, the per-source limits, the edge and the role split

*Written 2026-11-13 by `P16-TSK-015` (ADR-0145, `Proposed`), on master `fb0e014b`. Every value below is **derived** from §2 and §3.1,
**read** from the code, or computed from §3.4's **measured** costs at three replicas, and says which; none is measured at its own value. Each
names the run that would confirm or overturn it, and every such run is owed under `POSTPONED_ISSUES.md` PI-025 (the owner's word of
2026-11-13). Where the derivation gives the value already committed, the value stays and its derivation is what this section adds.*

### 4a.1 The autoscaler

**The metric: the pods' CPU against their request, and nothing else** (`deploy/base/horizontalpodautoscaler.yaml`). The first limit
measured is the single writer's CPU (§3.4), not the replicas'. Latency, the pool's queue and the request rate all rise when the writer is the
limit, and an autoscaler on any of them would add replicas there: more load and more connections on the resource that is already
saturated, and the budget of §4 spent for nothing. A replica's CPU rises only when the replicas are the limit, which is exactly when another
replica helps. Memory is not a metric: a JVM's heap does not shrink when its load does. A custom-metrics adapter is therefore not needed.

**The target: 70% of the 500m request, kept.** *Derived*, from §3.1's peak rates and §3.4's measured cost on the replicas of each
operation alone (the isolation run):

| Step | Reading | Kind |
|---|---|---|
| The replicas' CPU at the target's peak | Σ rate × cost: transfers 2.78 × 29 ms, payments 5.56 × 86 (their callbacks inside), top-ups 1.11 × 76, reads 55.6 × 3.7, sign-ins 6.67 × 68, merchant reads 11.1 × 6 = **1.37 cores** across the fleet. Cross-check: E30's step 3 measured 0.52 cores at 0.42 of the peak, 1.24 scaled. The larger is used | derived from measured costs |
| The fleet at that peak | 1.37 / (0.70 × 0.5) = 3.9: **four replicas**, each at 0.34 of a core, 68% of its request. Three would read 91% and add the fourth | derived |
| W2's burst on that fleet | 3 × 1.37 / 4 = 1.03 cores a pod for 60 s: **51% of its 2-core limit**. The autoscaler cannot answer 60 s anyway (its window, then a JVM's start), so the burst is carried by the standing fleet's headroom between request and limit | derived |
| The day's average | 1.37 / 2.4 = 0.57 cores: 38% of three pods' requests, so the floor of three holds off the busiest hour | derived |
| Production's ceiling of seven | 7 × 0.35 = 2.45 cores at the target, 1.8 times the peak's need. W2's burst held for good (it is not: 60 s) would want 12 pods; at seven they would run at 0.59 of a core, inside their limit. The single writer's CPU meets either first (§4) | derived |

A lower target buys headroom the limit already gives, and pays a pool of eight connections, a rebalance in every group and more schedule
competitors per replica (§5.1). A higher one changes nothing at the peak (three pods would still read above it) and less headroom between
the pods' share and the burst. 70% stays.

**The behaviour.**

| Field | Was | Now | Derivation |
|---|---|---|---|
| Scale-up window (`stabilizationWindowSeconds`) | 60 s | **120 s** | Twice W2's 60 s burst (§2). The autoscaler scales up to the lowest count it computed in the window, and with twice the burst's length that count is always one computed outside a burst, so a burst the standing fleet carries never adds a pod that arrives after it, two rebalances and a pool opened and closed (the oscillation the entry names). Held by `KubernetesManifestsTest#aBurstTheFleetCarriesNeverScalesIt`, which reads W2 here. A real rise costs one more minute, which shedding (ADR-0137) covers |
| Scale-up policy | one pod per 60 s | kept | Each step is a pool and a rebalance; three to seven in four minutes after the window |
| Scale-down window | 300 s | kept | At least one grace (117 s): a step read inside the last one's grace reads a fleet whose load is still moving. Now held by the manifest policy at the grace, where it was held at a minute (`[hpa-scale-down]`, its plant `hpa-stabilisation-grace`), and by `KubernetesManifestsTest#scaleDownCannotStackTerminatingPods`. Five of W2's bursts |
| Scale-down policy | one pod per 120 s | kept | At least the grace (held since Phase 15): one terminating pod at a time, the budget's (§4) |

**The pods and the quotas, kept.** The CPU request (500m) is a pod's share of the target's peak at four replicas (0.34 of a core) with the
target's margin; the limit (2) is its headroom through W2's burst (1.03). Production's memory (2 GiB) stays: `kind`'s 1 GiB pods carried E30's
first three steps at no more than 57% of their limits (§3.4), but over 277 s, and nothing has read a replica's memory over hours; a smaller
production pod waits on E39's soak (eight hours). Each overlay's quota is the formula times the pods (§4, the manifest policy), so it moves
only with them.

### 4a.2 The per-source limits (`THREAT_MODEL.md` §6 item 33 (b))

Each door's limit is set against two needs and one bound: **one person's need** in a window (the floor below which the limit refuses
somebody doing nothing wrong); **a shared address's need** at R6 (§2) in the busiest hour, with a W2 burst inside the window; and **what one
source gets** (the attacker's bound). *Derived*, every number kept (`app/src/main/resources/application.yaml`):

| Door | Limit per source, window | One person's need | A shared address at R6 | What one source gets |
|---|---|---|---|---|
| Sign-in | 100 per 15 min (1,000 per /48) | **11**: the identity lockout's ten failures and a success (read, `LockoutPolicy.current()`); beyond it the person is locked anyway | W3's 1.2 sign-ins a day, 10% in the busiest hour: 0.030 per active customer per window, 0.034 with a burst inside it; at R6 **34**, **68** if every sign-in is retried once. 100 carries 1,470 active customers behind one address at that; the /48's 1,000 carries 14,700 | 100 guesses per 15 min, 9,600 a day, each identity still locked at ten |
| Registration | 10 per hour (100 per /48) | **3**: one registration and two same-key retries (a retry counts, `OPERATIONS_RUNBOOK.md` §11.30) | W1–W8 state no acquisition rate. A base of W1's million built in a year is 2,740 a day, 274 in the busiest hour, 0.5% of it behind an R6 address: 1.4 an hour, 2.7 with a retry each | 10 identifiers tested an hour (T47) |
| Recovery request | 10 per hour (100 per /48) | **3**: a request and two retries | W1–W8 state no recovery rate; 10 an hour is 1% of an R6 address's customers every hour | 10 messages an hour (none is sent today: nothing delivers one) |
| Recovery completion | 20 per hour | **3** | as above | 20 tokens tried an hour |
| Channel verification | 20 per hour | **3** | as above | 20 codes tried an hour |

**The carrier-NAT finding, answered** (item 33 (b): "one device retrying spends the budget in seconds and everyone there is refused").
Two different things. **Volume**: legitimate traffic from a shared address is not refused at these numbers. Sign-in, the binding door,
carries R6's thousand active customers at the busiest hour's burst with every sign-in retried once and a margin of 1.47; the other doors'
needs at R6 are a few an hour. **Fate-sharing**: one device at that address can still spend the budget for everyone there for the rest of the
window, at most 15 minutes for sign-in. No number answers that: a limit one device can reach is reached for all, and a limit no device can
reach bounds no attacker. It needs a different key (a device-bound bucket, or failures counted rather than attempts), which is new behaviour
on the authentication path: `THREAT_MODEL.md` §6 item 67 (a), the owner's.

**Held by** `PerSourceLimitsAreCalibratedTest` (`app/src/test/java/com/finapp/app/abuse/`): every door's limit, in `application.yaml` and in every
overlay that sets one, is at least one person's need; sign-in's is at least R6's need with a retry each, every input (W2, W3, R6) read from
this page and the threshold from the code.

### 4a.3 The edge's per-source tiers, and the front door (`THREAT_MODEL.md` §6 item 22 (b))

**The edge's tiers** (`deploy/edge/base/public-edge.yaml`), each per source **per controller**: the public edge runs two
(`deploy/edge/base/controllers.yaml`), so a source's ceiling is twice each number. *Derived*, every number kept:

| Tier | Rate (burst), in flight | × 2 controllers | Its sources' need | Margin |
|---|---|---|---|---|
| Door | 10/s (20), 5 | 20/s, 10 | an R6 address's door requests at W2's **spike**: 1.2 sign-ins a day × 10% / 3,600 × 1,000 × 10 = **0.33/s** | 60 times |
| Session | 50/s (100), 20 | 100/s, 40 | an R6 address's customer requests at the spike: (0.5 + 1 + 0.2 + 10) a day × 10% / 3,600 × 1,000 × 10 = **3.25/s**; in flight at the slowest target (2 s), 6.5 | 30 times; 6 in flight |
| Machine | 200/s (400), 20 | 400/s, 40 | the platform's whole merchant traffic at the spike (W4's 11.1 reads and the 5.56 checkouts' creation a second at peak, × 10) = **167/s**; in flight at the 1 s target: 40 carries a merchant with up to 80% of it at W2's burst and 24% at its spike | 2.4 times on rate |
| Callback | 500/s (1000), 20 | 1,000/s, 40 | every callback at the spike (W4: two a payment, 11.1 a second at peak, × 10) = **111/s**; in flight at the callbacks' measured p99 (278 ms, §3.4): **31** | 9 times on rate; 1.3 in flight |

The holds' sum per source (5 + 20 + 20 + 20 at the 2 MiB cap, 130 MiB) stays under the 256 MiB spool, which `EdgeAllowListTest` holds.
`PerSourceLimitsAreCalibratedTest` holds the door's and session's rates against an R6 address at the spike and the callback tier's rate and
hold against W4's spike, every input read.

**The front door, designed only** (`PHASE_16_PLAN.md` §1.1: a real environment's; the plan's "item 32 (b)" is `THREAT_MODEL.md` §6 item 22
(b), the spool). In front of the edge a real environment gives each source address:

- **a request-rate rule** at the edge's door and session tiers across both controllers, **120 requests a second sustained** (36,000 in five
  minutes), bursts to 240: 33 times an R6 address's need at W2's spike, so a carrier's NAT of thirty times R6 would still pass, while one
  host's flood is dropped before it reaches a controller. Merchants' servers and providers are scoped by their own paths (the machine and
  callback routes) at those tiers' ceilings, 400 and 1,000 a second;
- **a cap on requests in flight** of **130** per address (the edge's holds, 65 per controller), which bounds one source's share of the
  spools at the body cap.

What it does not do: several sources together can still fill a controller's spool, as item 22 (b) says, and only the provider's
volumetric protection answers a crowd. Nothing here relies on a provider's rule: production's load balancer is layer 4 (the `ip`-target
network load balancer of `deploy/edge/overlays/production`, the client's address preserved), and a layer-7 rule in front of it would move
where TLS ends and whose address the edge believes (T40). The provider's own documentation is read when an environment chooses it: its
window, its smallest limit, and whether its rule can stand in front of a layer-4 balancer at all.

### 4a.4 The role split: not split (ADR-0145)

`PHASE_15_PLAN.md` C5 left an `api`/`worker` split to this measurement. **Decided: one Deployment, every replica every role.** The evidence,
all on this page:

- **Measured** (§3.4): at the engineering workload (E30's steps 1–3) every objective held with all 37 schedules, every consumer and the
  relay running on each of the three replicas; the replicas used 0.50–0.52 of their 6 cores, were throttled in under 0.1% of their CPU
  periods, and kept 324–326 live threads. Nothing on a replica was near a limit for the background roles to take from requests.
- **Measured**: the first limit is the single writer's CPU (E30's step 4). A split moves no work off it: the background roles' statements
  run on the same database whichever pod sends them.
- **Measured and read**: the relay fell behind at the target's checkout peak with no resource near its limit, because its drain is bounded
  by aggregates per relay (§3.4, ADR-0136). A worker role of fewer replicas would hold fewer relays and drain less.
- **Measured**: the census starved at the break was a shared **pool**, not CPU, and its answer is the shedding door (ADR-0137), whose
  follow-up, if E52 still starves it, is a reserved monitor connection before any split.

A split would also cost two Deployments, two budgets in §4's formula, and every schedule's competitors cut to the worker count (which must
stay at least two and leaderless). **What would overturn it**: E77 (below) showing the background roles raising request latency on the
replicas' own resources (their CPU throttled, their threads exhausted) while the database is below its limit; or E52 showing the monitors
starved with the door in place; or E69 and E70 showing claim contention at ten harming requests. Any of them reopens the split by an ADR.

### 4a.5 What the autoscaler does not tell anyone

No alert says the autoscaler sits at its ceiling or cannot read its metric (routed here from `P16-TSK-009`). **Decided**: a ticket when the
count has stood at the ceiling for 30 minutes, from the cluster watcher's `k8s_hpa_current_replicas` and `k8s_hpa_max_replicas`, and the
autoscaler's own `FailedGetResourceMetric` events kept by the watcher's event stream for the second. **Not built here**: the platform's
rules read only series captured from the pinned images (`infra/prometheus/platform-components-series.txt`,
`PlatformComponentAlertRulesTest`), and those two were never captured; capturing them takes a cluster. `THREAT_MODEL.md` §6 item 67 (b).

### 4a.6 The runs that confirm it

Every one built and owed under `POSTPONED_ISSUES.md` PI-025 (`RESILIENCE_EXPERIMENTS.md`, `OPERATIONS_RUNBOOK.md` §11.60):

- **E75**, the burst profile with the calibrated autoscaler (`REHEARSAL_LOAD_PROFILE=burst`, the autoscaler free between three and seven):
  W2's shape at the scale `kind` carries (the mix at 0.13 of the target's peak, each burst at 0.39, E30's step 3), with sign-ins at three times
  their target peak in every burst. *Derived*: the bursts take three pods to about 114% of their request (1.72 cores), above the target, and
  the count must not move; no door may refuse a request; the census green at every sample; latency and errors per stage recorded for
  `P16-TSK-029`'s judgement.
- **E76**, the entry's probe: E75 with the sign-in limit set to the lockout's threshold, below one person's need, on the run's own Deployment
  after its setup: the
  refusals of legitimate sign-ins counted and shown, and the run red.
- **E77**, the role split's reading (`REHEARSAL_LOAD_PROFILE=background`, three replicas): the engineering workload held 35 minutes, the
  run's day replayed through the audited operator door at the second stage's start, so every consumer and the relay work while the rate holds;
  each stage's latency and resources side by side (`load/role-split.tsv`), read against §4a.4's rule.
- The autoscaler's own scale under load is E70's, the peak at ten E69's (`P16-TSK-014`); the memory over hours E39's (`P16-TSK-008`); the
  monitors at the break E52's (`P16-TSK-016`).

---

## 5. Capacity and scaling strategy

From `PHASE_16_PLAN.md` §14. Each limit is measured before anything is changed, and a limit that comes from the architecture
rather than the infrastructure becomes an ADR before any change (ADR-0126 decision 5).

### 5.1 Per workload class

| Class | Strategy | Limit to measure | Note |
|---|---|---|---|
| Stateless APIs | Horizontal (HPA on CPU, calibrated by `P16-TSK-015`: §4a.1) | The connection budget (§4), then the primary | Adding replicas also adds schedule and consumer competitors |
| Payment orchestration | Horizontal; provider-bound | Provider latency × concurrency; the bulkhead | Never a retry of an ambiguous dispatch |
| Ledger operations | **Vertical** for the writer; hot rows by sub-accounts if measured (`PHASE_16_PLAN.md` §6.4) | Postings a second on the hottest row | No write scale-out without an ADR |
| Background workers (the 37 schedules of `DISTRIBUTED_EXECUTION.md` §3) | Leaderless on every replica, `SKIP LOCKED` claims; batch sizes | Claim contention at ten; batch throughput per tick | Phase 15's C5 left a role split (`api`/`worker`) to this measurement (`P16-TSK-015`); *(2026-11-13: not split, §4a.4, ADR-0145)* |
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
- **Partitioning** of Kafka topics (fixed at 12) and, if `P16-TSK-012` decides it by an ADR, of high-volume tables. *(2026-11-13: ADR-0142
  decides no table is partitioned at the target, §3.6, and names what would overturn it.)*
- **Workload isolation** by pools and bulkheads, and by a role split only if measured (`P16-TSK-015`). *(2026-11-13: no split, §4a.4.)*
- **Read scaling** only for the reporting pool (`PHASE_16_PLAN.md` §6.6). *(2026-11-13: ADR-0142 routes no replica; one primary serves
  every door, held by a guard, §3.6.)*
- **Write limitations**, stated: writes do not scale out. One primary holds the journal, and every arbiter is a row or a
  constraint on it.
- **Backpressure** by the outbox and by explicit shedding (`P16-TSK-016`).

### 5.3 Bottlenecks that need architecture, not infrastructure

These are candidates, each confirmed or struck by measurement:

- the per-currency hot rows (`PHASE_16_PLAN.md` §6.4);
- `settlement.source FOR UPDATE` held for a whole acceptance;
- risk's whole-day proofs, and the rescreening census counting each version whole;
- the single writer itself, at a volume far above the production target.

*(2026-11-13, `P16-TSK-007`, measured on `kind` at three replicas, §3.4.)* Two more, found by the runs: **the merchant API's payable
read**, a whole-history read that folds the merchant payable account's every line on each call, the database's CPU in E30's break
(routed to `P16-TSK-008`); and **the outbox relay's drain**, at most 8 aggregates a poll per replica each second, behind the target's
checkout peak with no resource near its limit (routed to `P16-TSK-013`). Neither is confirmed at the production target's shape (2,000
merchants, ten replicas), and neither has been changed. *(2026-11-13, `P16-TSK-013`: the relay's drain changed by ADR-0136, the ADR
this section asks for, and not yet measured: E47, owed under PI-025, §3.4's note.)*

*(2026-11-13, `P16-TSK-008`, read and derived, nothing measured: §3.5.)* The payable read's bound is ADR-0133 decision 3, proposed, its build
the owner's to place (`THREAT_MODEL.md` §6 item 55) *(2026-11-13: placed by the owner at (i), `P16-REM-001`; read as placed since)*. Read from the code beside it: **every balance decision folds the account's whole
history under its row lock**, so a hot account's hold grows with its age, measured by E38's pair. The per-currency hot rows, the
source lock, risk's whole-day proofs and the rescreening census are each ruled in `CURRENT_STATE.md`'s debt rows, and none has a
mechanism before E38 or E39 names it (ADR-0133 decision 2).

Each one confirmed becomes an ADR before any change.

---

## 6. Keeping this model true

- `P16-TSK-007` fills §3.4 and names the first limit. A later run replaces a cell only with its own named run. *(2026-11-13: filled
  from two runs at three replicas; the runs at seven, the normal, peak and sustained profiles and the planted slow statement are owed
  under `POSTPONED_ISSUES.md` PI-025.)*
- `P16-TSK-008` writes §3.5's readings and leaves its measured cells `—`: E38, E39 and E40 fill them, each cell naming its run, when
  PI-025 is run.
- `P16-TSK-012` writes §3.6's derived growth and reads, and leaves its measured cells `—`: E65, riding E39's run, fills them, each naming
  its run, when PI-025 is run.
- A change to W1–W8 is the owner's. It changes the derived cells that cite it, by a dated note.
- A change to the code that adds or removes an event on a flow changes §3.2. The counts were read on master `6981c167`.
- A change to the pool, the replicas, the surge or the server changes §4. `ConnectionPoolSizingGuard` holds the live arithmetic,
  and this page holds its reading.
- `P16-TSK-015` writes §4a's calibration from §2, §3.1 and §3.4. A changed W2, W3 or R6, a changed per-operation cost or a run of E75–E77
  changes the row that cites it, by a dated note; `PerSourceLimitsAreCalibratedTest` and `KubernetesManifestsTest` read W2, W3 and R6 here,
  so a changed assumption turns them red until the limits or the windows are derived again.
