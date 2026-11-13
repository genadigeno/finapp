# Timeouts and Retries

*Written 2026-11-13 by `P16-TSK-018` from `PHASE_16_PLAN.md` section 8, on master `1fbdf71c`. It is the one inventory of
every outbound call's bounds and of every retry the platform makes itself, each retry classified. `OutboundCallsAreBoundedTest`
holds it against the code: every class that builds an HTTP client is a row of section 2.1 and no other, every schedule is a row of
section 3.1 and no other, and what those rows say about bounds and jitter is what the code does. Nothing here is a measurement:
the provider experiments that would measure (E17, E18, E21) are built and their runs are owed under `POSTPONED_ISSUES.md` PI-025.
Nothing here calls the platform ready for production.*

---

## 1. The rule

From `PHASE_16_PLAN.md` section 8, binding on every row below: **never a blind retry of a financial command or of an external
operation whose outcome may be ambiguous.** A retry is safe only when it is one of:

- **(a)** idempotent at its arbiter: an idempotency key, a unique claim, a born-once row, a conditional transition on the
  database's clock;
- **(b)** a read or an inquiry, which changes nothing at the provider;
- **(c)** idempotent at the provider by our reference: the same `Idempotency-Key` (or the provider's own reference field) that
  the first attempt carried, so a second send is the provider's duplicate, never a second effect.

An ambiguous answer - a timeout, a `5xx`, a body that is not a parsed answer - becomes `UNKNOWN` (or the rail's equivalent) and
an inquiry resolves it (`INV-LIFE-03`, ADR-0046). It never becomes a resend on its own; a resend happens only after an inquiry
said the provider never saw the operation (`unrecognised`), and then under the first attempt's reference: class (c).

---

## 2. Every outbound call and its bounds

### 2.1 HTTP exchanges with providers

Three bounds, each a configured `Duration` (one setting per provider, all three from it):

- **Connect**: `HttpClient.Builder.connectTimeout`, how long a TCP and TLS connection may take.
- **Headers**: `HttpRequest.Builder.timeout`, from the start of each attempt until the response's headers. In JDK 21 this is
  all the request timeout covers: `MultiExchange` cancels its response timer as soon as the headers arrive, and the body is read
  with no timer.
- **Body**: `OutboundExchange.send`
  (`platform/src/main/java/com/finapp/platform/outbound/OutboundExchange.java`), from the headers' arrival to the body's last
  byte. Past it the body is failed with an `HttpTimeoutException`, the class every adapter already reads as a timeout, and its
  subscription cancelled, which closes the connection. With the JDK's two, every phase is bounded, so the exchange is bounded
  overall. The bound starts at the headers, not at the call, so that the JDK's own handling before them stands untouched, its
  retry of a refused connection included: a bound from the call cut that retry short and turned a refused connection into a
  timeout, which the instant scheme adapter's own test found on the bound's first form. *(Until
  2026-11-13 no exchange had this bound: a provider answering its headers and then stalling its body held the calling thread
  until the connection died. `HttpReferenceRateSource` read its body from a stream returned at the headers, with no bound at all.)*

A refused connection stays a `ConnectException` through all three, the one transport failure that is knowledge (nothing was
transmitted). Every adapter maps every other failure to its own "we do not know" (`INDETERMINATE`, `UNKNOWN`, `TIMEOUT`,
`Unavailable`), never to success or failure by assumption.

| Adapter | Provider and operations | Connect | Headers | Body | Setting (default; `kind`) | Called from | A timeout is |
|---|---|---|---|---|---|---|---|
| `PspWireClient` | Card PSP: authorize, capture, void, refund, dispute respond (POST, `Idempotency-Key`); operation, clearing and dispute inquiries (GET) | yes | yes | yes | `finapp.payments.provider.timeout` (PT2S; PT15S) | Request threads (confirmation, void, refund, dispute answers, the webhook door's inquiries); the card sweep, the dispute-response and hint sweeps, risk's actions | `INDETERMINATE`: the attempt `*_UNKNOWN`, inquired by the sweep |
| `SchemeWireClient` | Instant scheme: initiate, send, return, exchange (POST, `Idempotency-Key`); initiation, execution and return inquiries (GET) | yes | yes | yes | `finapp.payments.instant.timeout` (PT2S; not configured) | Request threads (pay-in confirmation, refunds, withdrawals, bank accounts, the instant webhook door's inquiries); the pay-in, return, withdrawal and hint sweeps, the loan payout | `INDETERMINATE`, inquired by its sweep |
| `SimulatedCorridorAdapter` | Corridor rails A and B: beneficiary exchange, send, recall (POST, `Idempotency-Key`); inquiry (GET) | yes | yes | yes | `finapp.corridor.provider.timeout`, `finapp.corridor.provider.b.timeout` (PT2S; not configured) | Request threads (cross-border payments and beneficiaries, the corridor webhook door); the outbound credit sweep | `INDETERMINATE`, inquired by the outbound credit sweep |
| `SimulatedPayoutProvider` | Payout rail: dispatch (POST, `Idempotency-Key`); query (GET) | yes | yes | yes | `finapp.merchant.payout.provider.timeout` (PT2S; PT15S) | Request threads (merchant and operator payouts); the payout resolution sweep (query only) | `UNKNOWN`, inquired by the payout sweep |
| `SimulatedPayoutDestinationTokenisation` | Payout destination tokenisation (POST) | yes | yes | yes | `finapp.merchant.payout-destination.tokenisation.timeout` (PT2S; not configured) | Request thread (an operator's destination proposal) | No token: the proposal refused, retried by the operator under its key |
| `SimulatedTokenisationAdapter` | Card tokenisation (POST) | yes | yes | yes | `finapp.paymentmethods.tokenisation.timeout` (PT2S; PT15S) | Request thread (a customer attaching a card) | No token: the attach refused, retried by the customer under its key |
| `SimulatedProviderClient` | KYC: identity, document, sanctions, PEP and adverse-media checks; counterparty screening (POST) | yes | yes | yes | `finapp.kyc.provider.timeout` (PT2S; not configured) | Request threads (a cross-border beneficiary, a quote whose re-screen is due); the screening retry, risk's re-screening | `INDETERMINATE`: the check open, re-asked within its budget |
| `SimulatedFxProviderAdapter` | FX providers A and B: firm quote, execution (POST, `Idempotency-Key`); execution inquiry (GET) | yes | yes | yes | `finapp.fx.provider.timeout`, `finapp.fx.provider.b.timeout` (PT2S; PT15S in the platform scopes) | Request threads (customer and cross-border quotes; the FX webhook door's inquiry); the cover sweep and its nudge pool (executions) | A quote refused; an execution indeterminate, inquired by the cover sweep |
| `HttpReferenceRateSource` | The reference rate source (GET) | yes | yes | yes | `finapp.fx.reference.timeout` (PT5S; PT15S in the platform scopes) | The rate fetch schedule only | `TIMEOUT`: the fetch counted failed; quotes refused once the reference is stale |
| `HttpSettlementReportCollector` | Settlement report sources (GET) | yes | yes | yes | `finapp.settlement.pull.timeout` (PT10S; not configured) | The settlement pull schedule; an operator's fetch | `TIMEOUT`: pulled again at the next permit |
| `SimulatedBureauAdapter` | Credit bureau report (POST, `Idempotency-Key`) | yes | yes | yes | none: never composed; `CreditBeans` refuses `finapp.credit.bureau.url` (unresolved question 13) | Nothing on any deployment today | `Unavailable`: the policy's fallback |
| `SimulatedFinancialDataAdapter` | Financial data summary (POST, `Idempotency-Key`) | yes | yes | yes | none: never composed; `CreditBeans` refuses `finapp.credit.findata.url` (unresolved question 14) | Nothing on any deployment today | `Unavailable`: the policy's fallback |
| `SimulatedSecondBureauAdapter` | Second bureau file (POST, `X-Request-Reference`) | yes | yes | yes | none: never composed | Nothing on any deployment today | `Unavailable`: the policy's fallback |

Every provider call is made between transactions, holding no database connection (ADR-0046). The defaults are a provider's
answer time on a real network; the `kind` overlay raises the five it names because the rehearsal's simulator is one small JVM
behind a TLS handshake on a shared machine (`deploy/overlays/kind/kustomization.yaml`).

### 2.2 The JDK's own retries inside an exchange

The JDK client retries an exchange itself in three cases, each safe:

| Case | Methods | Why it is safe |
|---|---|---|
| The connection was refused | every method, once | Nothing was transmitted (class (a): no first attempt exists) |
| A pooled connection was found closed before the response | `GET` and `HEAD` only | A read or an inquiry (class (b)). A `POST` is never retried: it fails, and the adapter reads it as ambiguous |
| An HTTP/2 peer says it did not process the request | every method | The provider says no first attempt was processed |

The second case would cover a `POST` if the JVM's `jdk.httpclient.enableAllMethodRetry` property were set: a blind resend of a
dispatch. Nothing sets it, and the guard refuses it anywhere in the code, its configuration, the manifests and the image.

### 2.3 Other outbound calls

| Call | Connect | Request | Overall | Retry |
|---|---|---|---|---|
| PostgreSQL, main and reporting pools (HikariCP) | the driver's `connectTimeout` 5 s | `socketTimeout` 300 s on both pools, every read of a connection the server does not answer given up there (ADR-0139; read "none per statement: no `socketTimeout`" until 2026-11-13); `statement_timeout` armed on the long reads, the abuse counts and the gauges | the pool's wait for a connection, `connection-timeout` 5 s; validation 2 s | none: a failed statement fails its transaction, and the caller's own retry is (a) by its key. The socket bound is `THREAT_MODEL.md` section 6 item 42 (h)'s, decided by the failover drill (ADR-0139): above every statement bound the code arms, so a cut read is the existing "committed, answer lost" case |
| Kafka producer (the outbox relay) | the client's own | `request.timeout.ms` 10 s, `max.block.ms` 10 s | `delivery.timeout.ms` 20 s | the client's, with `enable.idempotence` and `acks=all`: class (c), the broker deduplicates by producer id and sequence; backoff exponential with 20% jitter (kafka-clients 4.2.1's own) |
| Kafka consumer (the inbox) | the client's own | poll 1 s | `default.api.timeout.ms` 60 s | the client's, jittered; a failed handler is section 3.2's inbox row |
| OTLP export (traces and logs) | 2 s | 5 s | the batch processor's | none configured; a lost export loses telemetry, never a fact |

---

## 3. Every retry and its class

### 3.1 The schedules

Every schedule runs on every instance, on a fixed delay, on one thread of its own (`ScheduleDrain`). A tick re-attempts what
is due: that is the platform's own retry. "First tick" says how its first tick is placed: `jittered` is `TickPhase` (section 4),
a draw from one interval to just under two; `one interval` is exactly one interval after start. "Acting instances" is `every instance` when each instance acts on rows of its own claim, `one per window` when a permit lets one instance act per window cluster-wide, and `one at a time` when a lock lets one instance act while the others skip. A schedule that reaches anything beyond the database from `every instance` starts `jittered`; the guard holds it.

| Schedule | Interval (default) | Reaches | Acting instances | What a tick re-attempts, and why it is safe | Class | First tick |
|---|---|---|---|---|---|---|
| `PaymentSweeperSchedule` | `finapp.payments.sweeper.poll-interval` (PT30S) | the card PSP | every instance | Inquiries of aged dispatched and unknown card operations; a capture or void re-dispatched, a refund re-sent, each under its stored reference after the inquiry; held payments voided under their claim key | (b), (c) | jittered |
| `WithdrawalResolutionSchedule` | PT30S | the instant scheme | every instance | Inquiries of unknown withdrawals; holds expired on the database | (b), (a) | jittered |
| `PayInResolutionSchedule` | PT30S | the instant scheme | every instance | Inquiries of waiting pay-ins; an initiation never acknowledged re-sent under a compare-and-set permit and its reference | (b), (c) | jittered |
| `ReturnResolutionSchedule` | PT30S | the instant scheme | every instance | Inquiries of unknown returns; a return the scheme never saw re-sent under its send permit and reference | (b), (c) | jittered |
| `CallbackHintResolutionSchedule` | PT30S | the card PSP, the instant scheme | every instance | The inquiries a callback hinted at, one asker per hint and round by a compare-and-set permit; hints expired on the database | (b) | jittered |
| `DisputeResponseResolutionSchedule` | PT30S | the card PSP | every instance | Inquiries of unknown dispute answers; an answer the PSP never saw re-sent under its send permit and reference | (b), (c) | jittered |
| `OutboundCreditResolutionSchedule` | PT30S | the corridor rails | every instance | Inquiries of unknown outbound credits; a pending recall sent under its reference | (b), (c) | jittered |
| `MerchantPayoutResolutionSchedule` | PT30S | the payout rail | every instance | Inquiries of unknown payouts | (b) | jittered |
| `FxCoverSchedule` | PT5S | the FX providers | every instance | Cover executions and their inquiries, under the attempt's reference; a requote under a conditional transition | (b), (c) | jittered |
| `CounterpartyScreeningRetrySchedule` | PT30S | the KYC provider | every instance | Screenings the provider did not answer, re-asked under the screening's id within their permit | (c) | jittered |
| `CreditDataRetrySchedule` | PT30S | the credit data sources | every instance | Data requests unanswered or unavailable, re-asked under the same reference within their deadline | (c) | jittered |
| `CreditDecisionProgressSchedule` | PT5S | the credit data sources | every instance | A request's progress: its first pull, under its reference, after the step's transaction | (a), (c) | jittered |
| `LoanPayoutSchedule` | PT5S | the instant scheme | every instance | A loan's payout instruction, under its idempotency claim and the end-to-end reference | (a), (c) | jittered |
| `RiskActionSchedule` | PT5S | the card PSP, the instant scheme, the KYC provider | every instance | Downstream actions under their claim keys and permits; re-screenings, each a new check keyed by its ordinal, an in-flight one never re-dispatched; filings to an in-process provider | (a), (c) | jittered |
| `OutboxRelaySchedule` | `finapp.outbox.poll-interval` (PT1S) | the Kafka broker | every instance | Publication of due outbox rows, one aggregate per advisory lock; a row's own backoff is `RetryPolicy` (section 3.2) | (c) | jittered |
| `FxRateFetchSchedule` | PT15S | the reference rate source | one per window | One fetch per source per window, by a permit on the database's clock | (b) | one interval |
| `SettlementPullSchedule` | PT5M | the settlement report sources | one per window | One pull per source and key per window, by a permit; the content address deduplicates a report pulled twice | (b) | one interval |
| `AccountingCloseSchedule` | PT1H, PT30S, PT1M, PT1M by leg | the database; the regulatory reporting provider, in-process, no network | every instance | Period opening, close checks, orphaned report runs, the regulatory inquiry: each under a lease or a claim; a resend only under its permit | (a) | one interval |
| `SourceCensusSchedule` | PT1M | the database only | one at a time | The census's walk from its single head row | (a) | one interval |
| `BnplDisputeSchedule` | PT60S | the database only | every instance | Claimed rows under a re-stamped permit | (a) | one interval |
| `BnplOriginationSchedule` | PT5S | the database only | every instance | Applications and offers under `SKIP LOCKED` claims | (a) | one interval |
| `BnplServicingSchedule` | PT30S | the database only | every instance | Servicing steps under `SKIP LOCKED` claims | (a) | one interval |
| `CheckoutExpirySweeperSchedule` | PT30S | the database only | every instance | Expiries by a conditional transition under a row lock | (a) | one interval |
| `RetentionSweepSchedule` | PT5M, PT5M, PT1M | the database only | every instance | Deletions under `SKIP LOCKED` inside the sweep's functions | (a) | one interval |
| `FxQuoteExpirySchedule` | PT5S | the database only | every instance | Expiries under a claiming update | (a) | one interval |
| `LoanDisbursementSchedule` | PT5S | the database only | every instance | Disbursements under `SKIP LOCKED` claims | (a) | one interval |
| `LoanOriginationSchedule` | PT5S | the database only | every instance | Applications and offers under `SKIP LOCKED` claims | (a) | one interval |
| `LoanServicingSchedule` | PT1M | the database only | every instance | Servicing steps under `SKIP LOCKED` claims | (a) | one interval |
| `PayoutDestinationEffectuationSchedule` | PT30S | the database only | every instance | Due destinations under a row lock and a conditional transition | (a) | one interval |
| `PayoutReturnSchedule` | PT30S | the database only | every instance | Waiting returns under a row lock | (a) | one interval |
| `OutboundReturnSchedule` | PT30S | the database only | every instance | Waiting returns under a row lock | (a) | one interval |
| `ReconciliationSchedule` | PT15S | the database only | one at a time | Matching chunks under an advisory lock per source | (a) | one interval |
| `ReconciliationSweepSchedule` | PT60S | the database only | every instance | Conditional writes | (a) | one interval |
| `RiskMonitoringSchedule` | PT5S | the database only | every instance | Intake per source under a watermark claim; profiles and cases under claims | (a) | one interval |
| `SettlementIntakeSchedule` | PT15S | the database only | every instance | Due files under `SKIP LOCKED` | (a) | one interval |

### 3.2 Retries that are not a schedule's tick

| Retry | What is retried | Bound | Jitter | Class |
|---|---|---|---|---|
| A client's retry under its `Idempotency-Key` | A money-moving command | The key's claim: a second request answers the stored result, or `409` while the first is in progress | the client's | (a) |
| The outbox relay's row backoff (`RetryPolicy`) | A publication that failed | 1 s doubling to 5 min, abandoned after 14 attempts to the dead-letter doors | none, by its own decision: the herd is bounded by construction, at most 8 aggregates of 100 events per poll per instance, one advisory lock per aggregate; E09 (`P16-TSK-013`) measures it, owed under PI-025 | (c), and the consumers' inbox (a) |
| The inbox's failed handler | A delivered record whose handler failed | a fixed 1 s pause, the partition stalled on the record; parked once it has failed 5 times and for 2 minutes | none: one partition, one consumer | (a): the inbox deduplicates by event id |
| The dead-letter doors (retry abandoned, release parked, replay) | An abandoned or parked event, by a person | refused past the dedupe window unless every consumer is naturally idempotent; at most 1000 events a replay | a person | (a) |
| A KYC check's unknown answer | A check the provider did not answer | 3 unknowns of a type, then a person reviews | none | (a). Only the case's run re-asks, and no production path runs a case's checks today (`THREAT_MODEL.md` section 6 item 60) |
| Risk's re-screening | A party's screening | 5 min doubling to 6 h, 12 attempts, then failed | none: one claim per permit | (a): each re-ask is a new check keyed by its ordinal |
| A sweep's failed or unreadable row | A row a sweep could not judge | 60 s doubling to 10 min, on the database's clock | none: one record per window across instances | (a) |
| The posting cutoff's lock wait | A lock on the period, inside its transaction | 5 tries, 2 s apart, through a savepoint | none | (a) |
| The migrator | Its first connection; a lock or a deadlock | 10 tries 10 s apart; 5 tries 5 s apart | none: one migrator Job | (a): each migration is versioned and applied once |
| The JDK client, the Kafka clients | section 2.2, section 2.3 | | | (a), (b), (c) |

No loop retries a transaction on a serialization failure (`40001`) or a deadlock (`40P01`) outside the migrator: such a failure fails its transaction, and the caller's own retry under its key is (a).

---

## 4. Jitter

**Where several instances retry one dependency, their first ticks are spread.** The fifteen schedules of section 3.1 that reach a
provider or the broker from every instance start their first tick at a draw from one interval to just under two (`TickPhase`),
once per start. Instances started together - a cold start, several replicas added at once, a fleet made ready together - would
otherwise tick in step for as long as they run: a fixed delay drifts only by each tick's own duration. In step, every instance's
inquiries, re-asks and re-sends reach a recovering provider at the same moment of every interval. Nothing correct rests on the
spread: every claim and transition is the database's.

**Where it is not added, and why.** The two schedules that act once per window (the rate fetch, the settlement pull) send one
request per window cluster-wide whatever their phases. The database-only schedules reach nothing outside. The outbox relay's
per-row backoff stays unjittered, as `RetryPolicy` decided: its herd is bounded by the poll's batch, not by the backlog, and is
spread by the relay schedule's own jittered phase; jitter there waits for E09's measured herd. The Kafka clients jitter their own
backoff. The inbox's pause is one consumer's, on one partition.

---

## 5. Provider outages and slowness, and automatic rail availability (owner decision C12)

**The experiments.** `RESILIENCE_EXPERIMENTS.md` E17 (the card PSP unavailable, then silent past the timeout), E18 (the FX
provider and the reference rate source unavailable), E21 (every provider slowed to two, then ten times its steady latency) are
built in the `kind` harness's `experiments` scope with the simulator's degradation controls (`OPERATIONS_RUNBOOK.md` section
11.53). E19 (KYC) refuses to run: no production path runs a customer's checks and `kind` configures no KYC provider
(`THREAT_MODEL.md` section 6 item 60). E20 (the bureau) rests on the recorded fail-safe: no deployment can configure a bureau, and
the owner decided on 2026-11-13 that none is built for `kind` in Phase 16 (unresolved question 13). Every run is owed under
`POSTPONED_ISSUES.md` PI-025.

**Failure rates per rail.** Each run records, per rail, the share of its acting judgements that failed or were left unknown
(`finapp.payments.rail.outcome` by rail and outcome, over the fault window and over the steady state before it). None is measured
yet.

**C12's decision, 2026-11-13.** Automatic rail availability is not built. The decision C12 accepted needs measured failure rates,
and there are none: the runs that would measure them are owed (PI-025), and on `kind` they would measure a simulator, which says
how the platform behaves when a rail fails and nothing about how often a real rail does (`RESILIENCE_EXPERIMENTS.md` D05).
Availability stays an operator's recorded, audited fact (ADR-0060 decision 4); `DECISIONS.md` section Deliberately Deferred
carries the row. If it is built once real failure rates exist, it writes the same audited fact an operator writes, as a bounded
automatic suspension an operator confirms, and it reads the rate from the committed judgements (`finapp.payments.rail.outcome`
counts only acting judgements, after commit), never an instance's own count.

---

## 6. What this inventory does not claim

- That any bound was measured under load, or that any default suits a real provider. The defaults are reasoned, not measured.
- That a provider experiment ran. E17, E18 and E21 are built and not run; E19 cannot run; E20 rests on the fail-safe.
- That the threads a slow provider can hold are bounded in number: each call is bounded in time, and the bulkheads that bound
  how many calls wait at once are `P16-TSK-016`'s. A refused hinted inquiry (`CallbackInquiryBulkhead`) is counted by no meter
  today, routed there.
