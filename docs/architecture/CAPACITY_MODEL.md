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
measures the skew by key.

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
second in the busiest hour. The read was **measured** as E30's first limit (§3.4). Its bound is ADR-0133 decision 3, proposed, and its build
is the owner's to place (`THREAT_MODEL.md` §6 item 55). The decision path's fold is measured by E38's pair.

**The sweeps' failure records in a storm** (routed by `P16-TSK-017`). Read: each of the eight listings' `NOT EXISTS` probe is one primary-key
probe of `(leg, row_id)` per candidate the listing reads. With the tables empty it adds that probe and no more. In a storm that backs off
every candidate, a listing's `LIMIT` never stops early, so each tick of each instance reads every candidate, the card legs with their
lateral age read too. The cost is the candidate count, not the batch. The record table takes at most one write per row per backoff window
(1 minute doubling to 10), and its updates change no indexed column, so they can be heap-only. E40 measures the plans, the latency, the
write rate and the bloat. Measured: —.

**The transactional index build** (`THREAT_MODEL.md` §6 item 42 (b)). Read: a plain `CREATE INDEX` holds `SHARE` on its table for the whole
build, so every insert into the journal, and with it every posting, waits that long. The migrator's `lock_timeout` bounds only the wait for
the lock. E39 measures the stop on the soak's journal, and the build rate extrapolates it to the target's. Measured: —.

**The plans and the soak's growth** (`PHASE_16_PLAN.md` §6.5, §6.7). E39 writes the top statements' plans, every sequential scan, the named
tables' growth and dead rows, autovacuum's runs, the checkpoints and the WAL a money movement. Measured: —.

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

*(2026-11-13, `P16-TSK-007`, measured on `kind` at three replicas, §3.4.)* Two more, found by the runs: **the merchant API's payable
read**, a whole-history read that folds the merchant payable account's every line on each call, the database's CPU in E30's break
(routed to `P16-TSK-008`); and **the outbox relay's drain**, at most 8 aggregates a poll per replica each second, behind the target's
checkout peak with no resource near its limit (routed to `P16-TSK-013`). Neither is confirmed at the production target's shape (2,000
merchants, ten replicas), and neither has been changed.

*(2026-11-13, `P16-TSK-008`, read and derived, nothing measured: §3.5.)* The payable read's bound is ADR-0133 decision 3, proposed, its build
the owner's to place (`THREAT_MODEL.md` §6 item 55). Read from the code beside it: **every balance decision folds the account's whole
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
- A change to W1–W8 is the owner's. It changes the derived cells that cite it, by a dated note.
- A change to the code that adds or removes an event on a flow changes §3.2. The counts were read on master `6981c167`.
- A change to the pool, the replicas, the surge or the server changes §4. `ConnectionPoolSizingGuard` holds the live arithmetic,
  and this page holds its reading.
