# Event Architecture

## Event Categories

### Domain Event
Represents a meaningful state change inside a bounded context.

### Integration Event
Represents a stable contract intended for consumers outside the originating context.

### Command
Requests an action; it is not a fact.

### Query
Requests state; it is not a command or event.

## Event Metadata

Important events should carry:
- eventId
- eventType
- aggregateId
- aggregateType where useful
- occurredAt
- producer
- eventVersion
- schemaVersion
- correlationId
- causationId

### The two versions

The list above names `eventVersion` and `schemaVersion` and defines neither. They are different
things, and collapsing them looks harmless until the first envelope migration:

| Field | Versions what | Changes when |
|-------|---------------|--------------|
| `eventVersion` | the **event type's contract** | `TransferCompleted` starts meaning something different |
| `schemaVersion` | the **envelope's own structure** | the metadata layout changes |

`eventVersion` is what lets a consumer refuse an event whose meaning it does not understand.
`schemaVersion` is what lets it parse an envelope written by an older producer *at all*, which
is why it is the first field in the canonical form — a consumer that cannot parse the envelope
cannot read the field telling it which layout to expect unless that field never moves.

With one version, there is no way to say "the metadata moved but the event means the same
thing", and every consumer must be redeployed in step with every producer.

### Where the envelope lives

`EventEnvelope` is in the shared kernel (`MODULE_ARCHITECTURE.md` §2), and it carries **metadata
only** — no payload. That is what lets an outbox relay, a dead-letter tool and a consumer's
deduplication read, route and store an event without deserialising anything domain-specific, and
it means a log line containing an envelope can never spill event contents (`INV-AUD-02`).

The **wire format is deliberately not defined there**: choosing one would commit the shared
kernel to a serialisation library. The envelope provides a canonical textual form whose field
set and order are pinned by test, so the contract cannot drift silently; the transport format is
the outbox's and the relay's decision (`P0-EPIC-06`).

### The stored payload format — decided by the first producer (`P1-TSK-006`)

The *broker* wire format was settled by the first adapter (`P2-TSK-001`,
`KafkaEventPublisher`), exactly where this section deferred it to: **the record value is the
payload bytes verbatim; the ten envelope fields plus the payload media type ride as
`finapp.`-prefixed record headers; the record key is the aggregate identifier**, so Kafka's
partitioner keeps one aggregate on one partition and the relay's per-aggregate ordering is an
ordering consumers actually observe. **Topics are one per producing module**
(`finapp.identity`, `finapp.party`, …), a small stable set, with `finapp.eventType` in the
headers for consumer-side filtering — revisit trigger: a topic whose consumers' interests
diverge materially. `finapp.eventId` on every record is the consumer's dedupe key
(`INV-IDEM-04`), and delivery is **at-least-once, said plainly**: a relay crash between broker
acknowledgement and the publication mark republishes with the same `eventId`
(`KafkaOutboxDeliveryKafkaTest` demonstrates the duplicate rather than hiding it).

The line below records what this section said until then, because the deferral was itself a
decision: the wire format was the adapter's and stayed open until an adapter existed. The format of the bytes
**in the outbox row** could not stay open past the first module that emits an event, so it is
settled here as ADR-0005's recorded follow-up rather than as a new decision:

**`application/json`, a flat object of identifiers and enumerated names, built by
`platform.outbox.EventPayload`.**

**It is a builder with a charset rather than an object mapper, and that is the point.**
`INV-AUD-02` keeps personal data out of event payloads and `PHASE_1_PLAN.md` §4 states the rule for
every Phase 1 event — *no credential material and no unnecessary PII*. A general mapper would
serialise `put("displayName", name)` happily; `EventPayload` rejects any value that is not an
identifier or an enum constant, so the accidental disclosure is a failing test instead. It also
means **no value that reaches the output ever needs escaping**, which is asserted rather than
assumed.

**The limit is stated:** this is not a general event serialiser and must not become one. An event
that genuinely needs a nested object, a decimal or a list needs the wire-format decision taken, not
worked around — at which point `EventPayload` is replaced rather than extended.

**A monetary amount has its wire format** (recorded by the Phase 6 review, `P6-DOC-001`, for the
decision `P6-TSK-005` took without writing it here): its **minor units as a base-10 integer
string, beside the ISO 4217 code in a sibling field** — `"grossMinor":"10000"`,
`"currency":"EUR"` — never a decimal and never a floating-point number, the scale being the
currency's own (ADR-0003). A digit string is an inert value, so `EventPayload`'s guarantees hold:
nothing needs escaping and nothing personal is carried. Only an event whose fact *is* an amount
carries one: `merchant.FeeAssessed` (gross, fee and net under the pinned version, so a consumer
can reproduce the fee from the version alone — `INV-HIST-04` on the wire) and
`merchant.FeeReturned`; and, since Phase 9, the FX and cross-border facts whose subject is an amount
— `fx.FxQuoteIssued`, `fx.FxTradeExecuted`, the cover execution, `crossborder.CrossBorderPaymentInitiated` — which carry
each amount as `<name>Minor`, `<name>Currency` and `<name>Scale` siblings (the scale explicit, because five
currencies at three scales now travel; *corrected by the Phase 9 → 10 transition, 2026-10-07, which also gave
`CrossBorderPaymentInitiated` the currencies it had omitted*). A posting reference on the wire (`fx-trade-<id>`,
`outbound-credit-<id>`) is an identifier, not the stored ledger key (`EventPayload` admits no colon). Every other
event names identifiers, and a consumer that needs an amount reads it from its owner. An amount on the wire is information, never the balance: the ledger stays
the record (`INV-EVT-02`).

*(`P15-TSK-027`, 2026-11-08: every `<name>Minor` field now has its scale beside it, so "the scale being the currency's
own" describes only the version 1 records still on the wire. `merchant.FeeAssessed` and `merchant.FeeReturned` carry
`scale` at version 2 (`X-TSK-014`), and `crossborder.CrossBorderPaymentReturned` its returned amount at version 2, as
`returnedMinor`, `returnedCurrency` and `returnedScale`. `credit.CreditDecisionRequested`, `credit.CreditDecisionRecorded`
and `lending.LoanOffered`, written after Phase 9 without one, gained `scale` under their existing version. Later phases
added amount-bearing events this paragraph never listed, so "every other event names identifiers" stopped being true
with lending: the catalogue in §Schema evolution, not this list, says which events carry an amount.)*

### Causation at the root of a flow

`Correlation` leaves `causationId` **null** at a flow's root, deliberately, so a root is
distinguishable from a cycle. `EventEnvelope` requires it **non-null**, because every event has a
cause. An HTTP-initiated event sits exactly between those two rules: no message caused it.

**The request caused it.** A producer at a flow root sets `causationId` to the flow's correlation
identifier. That looks self-referential and is not: the correlation identifier names a real,
recorded thing — it is on the idempotency record and on the audit record written in the same
transaction — so the causal chain terminates at the request rather than at nothing. Minting a fresh
identifier there would be worse, because it would point at something that exists nowhere.

## Publication

Events are published by the **outbox relay** (`P0-TSK-020`) and by nothing else — enforced by
`nothingPublishesToABrokerDirectly`.

| Property | What the platform provides |
|----------|----------------------------|
| Delivery | **At least once.** The relay publishes, then records publication; a crash between the two republishes on restart |
| Order | Preserved **per aggregate**. The aggregate id is the partition key, and the relay stops at the first event of an aggregate it cannot publish rather than going around it |
| Retry | Exponential backoff with a ceiling, counted on the row |
| Abandonment | After a bounded number of attempts a row is dead-lettered. It is **not** skipped: it blocks its aggregate until an operator resolves it |
| Latency | Bounded by the poll interval, not immediate (ADR-0005) |

**Abandonment blocks rather than skips**, deliberately. Skipping a poisoned event and carrying
on is quiet — consumers receive events 1 and 3 with no way to know 2 existed — and an
undetectable gap in a financial event stream is worse than a stall somebody has to look at.

**Exactly-once is never claimed.** What is exactly-once is the *effect* at a consumer that
deduplicates on `eventId` (`INV-IDEM-04`), which is the consumer's property and not the relay's.
This is why `eventId` is minted with the event and never regenerated at publication.

### Handling an abandoned event

ADR-0005 requires poison messages to have a documented procedure, and abandonment is not
self-healing: the aggregate stays stalled until a person acts. The doors are `P15-TSK-028`'s: no
raw `UPDATE` remains in this procedure, and every act below is keyed, reasoned, audited and on a
`MULTI_FACTOR` session (`EVENT_OPERATE`, held by `EVENT_OPERATOR`).

An abandoned row announces itself three times: `outbox.EventAbandoned` in the audit trail, in the
transaction that abandoned it; an `ERROR` naming the event, the aggregate and the attempt count;
and a `WARN` on every subsequent cycle saying the aggregate is blocked behind it. Every one of
them carries the originating flow's correlation identifier, so the event can be traced back to
the transfer or payment that produced it.

`GET /v1/operator/events/dead-letters` lists what is abandoned, oldest first, with the failure's
class and never its message or the payload.

Two resolutions, and the choice is a judgement about the event, never a default:

- **Retry it** — the cause was environmental (a broker misconfiguration, an expired credential,
  a topic that did not exist) and the event is still correct to publish:
  `POST /v1/operator/events/abandoned/{eventId}/retry`, with a reason. The relay takes it again in
  its aggregate's order. Refused while a discard stands, and at or past the dedupe window unless every
  consumer of the type is naturally idempotent (`deadletter.RetryOutsideDedupeWindow`): an earlier attempt
  whose acknowledgement was lost reached the broker and left no mark, so nothing can prove a consumer never
  applied it. That default is the owner's to confirm (`THREAT_MODEL.md` §6 item 17).
- **Abandon it permanently** — the event is genuinely unpublishable. This is a decision that
  consumers will never see a fact that happened, so it needs the same scrutiny as a manual
  adjustment: `POST /v1/operator/events/discards` proposes it with a reason, and a **different
  person** approves it, `POST /v1/operator/events/discards/{discardId}/approval`
  (`outbox.EventDiscarded`). The row is **not** deleted; it is the evidence that the gap exists.
  It stops being pending, so its aggregate goes on, and it keeps `dead_lettered_at`, so a relay of
  the previous release still stops at it.

**Never resolve an abandoned row by deleting it.** The row is the only record that an event
which should have been published was not, and the outbox is where that question is answered.

### A poison record, parked

Since `P15-TSK-028` the receiver no longer stalls a partition without end. A record that fails
**for a reason of its own** is retried, counted in `platform.inbox_parked_record` in PostgreSQL, and
parked once it has failed five times **and** for two minutes on the database's clock
(`finapp.inbox.park-after-attempts`, `finapp.inbox.park-after-failing-for`). A parked record is
acknowledged only after the park has committed, so its partition goes on and nothing is
acknowledged on the strength of a park that did not happen. It is counted
(`finapp.deadletter.parked{group}`), audited (`inbox.RecordParked`), and kept; a redelivery of it is
acknowledged without running the handler.

**A failure that says nothing about the record never counts** (`InboxFailures`): a database that
blinked, a pool that ran dry, a contended claim, a refused broker credential, or a build too old to
read a version (`EventNotYetReadable`, which stalls as it always did until a deploy that reads it).

Release it once the cause is fixed, `POST /v1/operator/events/parked/{parkId}/release`, which
clears its count and republishes its event under the replay rule below; or discard it, four-eyes,
through the same `discards` door. A record that could not even be parsed never had an outbox event,
so a discard is its only way on.

### Replay

`POST /v1/operator/events/replays` republishes the published events of **one type over a time
window** of first publication, all or nothing, with a reason (`outbox.EventReplayOrdered`). It is how
a handler added in a release is served over its rollout and rollback windows, and how a group
stopped past topic retention catches up.

**A replay can never apply an effect twice, because the inbox primary key refuses the duplicate
while its row exists.** So:

- **Refused past the dedupe window.** An event at or older than `finapp.inbox.retention` (14 days)
  may have lost its row to the sweep, and the whole command is refused. The window is the value the
  dedupe rows were dated with (`InboxConsumer#retention`), not a copy, and an event's age is counted from when
  it occurred, or from the last replay ordered of it, never from a publication: a publication whose
  acknowledgement was lost reached the broker and left no mark.
- **The dedupe rows are extended in the same transaction.** Inside the window is not enough: an event
  a moment younger than the limit could have its row swept before a lagging consumer reads the
  replayed record. So the order keeps every consumer's row, found from the table itself, for a full
  retention from now, longer than the broker can still hold the record.
- **Unless every consumer of the type is naturally idempotent**, by the mark on the handler
  (`InboxEventHandler#naturallyIdempotent`, off by default). No handler carries it today.

A replay is requeued for the relay, so it inherits per-aggregate order, backoff and abandonment. A row
behind an abandoned one waits for it, so a retry or a discard of that row judges the replays it sets
moving: one still inside the window is extended, and one past it is withdrawn, back to its first publication
and not sent again (a refusal would leave the aggregate with no door, because a row that is merely waiting
cannot be discarded). A parked record is not revived by a replay: releasing one is its own act. Replay at volume is
Phase 16's: one command requeues at most 1000 events.

**The transport adapters exist since Phase 2, one per direction.** `KafkaEventPublisher`
(`P2-TSK-001`) settled the wire format, the topic scheme and the acknowledgement configuration
described above; `KafkaEventReceiver` (`P2-TSK-002`) is the consuming shell. *(This paragraph
said the adapter was "deliberately absent" — true when written, corrected by the task that added
the second adapter after the first had left it stale.)* Each lives in its own package, the only
two `NoDirectBrokerPublicationRulesTest` exempts, so a broker client can appear nowhere else.

## Consumption

Consumers deduplicate through the **inbox** (`P0-TSK-021`): a dedupe record keyed on
`(consumer, dedupe_key)`, written **in the same transaction as the side effect**. The record
exists if and only if the effect happened, which is what makes the relay's at-least-once delivery
acceptable.

**The dedupe key is scoped to the consumer.** One event legitimately has many consumers and each
must handle it once; keying on the message alone would let whichever consumer got there first
silently suppress every other one. For a platform event the key is the envelope's `eventId`,
fixed at creation so a redelivery presents the same value; for a webhook it is whatever the
provider guarantees stable across its own retries, which the provider's adapter decides.

**A duplicate is not an error.** At-least-once delivery makes redelivery routine, so a duplicate
is an outcome (`SKIPPED_DUPLICATE`) and is logged at debug. Two instances racing the same
redelivery is reported as `CONTENDED` after a short bounded wait, and the correct response is to
leave the message **unacknowledged** and let it be redelivered — the other transaction may yet
roll back. A consumer never waits long for a race it does not need to win.

**Retention is a correctness bound**, not housekeeping: a dedupe record that expires while the
producer can still redeliver admits exactly the duplicate effect it existed to refuse.
`DATA_MIGRATIONS.md` §9 states the policy and what the window must exceed — for Kafka the
redelivery window is bounded by topic retention, which is what the shell's default dedupe
retention is sized against. *(Since `P15-TSK-026` the bound is asserted rather than sized by
hand, and it is retention **plus one segment** plus the broker's retention check: §Topics below.)*

**The shell acknowledges only what has committed** (`P2-TSK-002`). `KafkaEventReceiver` commits
the broker offset strictly after the inbox transaction commits, with auto-commit disabled; the
two commits cannot be atomic, and every failure between them — crash, rebalance, lost connection
— resolves as a redelivery into the dedupe, the safe direction. A record that cannot be handled
is **seeked back to rather than skipped**: acknowledging past it would be an undetectable gap,
the relay's block-don't-skip rule on the consuming side. Consumer-group offsets are transport
bookkeeping, never truth (`DISTRIBUTED_EXECUTION.md` §3): losing them replays the topic and the
inbox absorbs it.

## Topics, transport security and consumer groups — *Implemented* (`P15-TSK-026`)

Kafka stays transport (`INV-EVT-02`). What a deployed broker needs from the platform is three
things, each held by a test rather than a convention.

**Every topic is declared.** A deployed broker creates no topic on first use
(`auto.create.topics.enable=false`), so a producer naming an undeclared topic blocks until its
acknowledgement times out and the event's aggregate stalls until the outbox dead-letters it.
[`infra/kafka/topics.yaml`](../../infra/kafka/topics.yaml) is the catalogue: every topic
`finapp.<producer>`, its configuration by class, and the permissions of each principal.
`TopicCatalogueTest` derives the producers from the compiled code — every `new EventEnvelope(...)`
in production output and the constant it names as its producer, read by a stack simulation and
cross-checked against ArchUnit's import of the same construction sites — maps each through
`KafkaEventPublisher.topicFor`, and fails on a produced topic the catalogue lacks, on a declared
topic nothing produces, and on a producer that is not a compile-time constant. Nineteen topics
today, one per producing module. The local compose broker still creates topics on first use; it is
loopback development and the catalogue does not apply to it.

**Retention is below the inbox's dedupe window.** The broker deletes whole segments, and a segment
only once its *newest* record is past retention, so a record can outlive `retention.ms` by up to one
`segment.ms`, and by the broker's retention check interval. With the broker defaults (seven-day
retention, seven-day segments) a record could be redelivered fourteen days after it was written —
the whole of the inbox's window. So the one class today, `domain-events`, takes `retention.ms` of 7
days, `segment.ms` of 1 day, `cleanup.policy=delete` (compaction keeps a key's last record
indefinitely), `message.timestamp.type=LogAppendTime` (retention on the broker's clock, not a
producing replica's), 12 partitions, replication 3 and `min.insync.replicas` 2; and the test asserts
`retention.ms + segment.ms + log.retention.check.interval.ms < finapp.inbox.retention` for every
topic, reading the window from `EventingBeans`' own `@Value` and the committed `application.yaml`:
7 days + 1 day + 5 minutes against 14 days. A deployment that lowers `FINAPP_INBOX_RETENTION` is not
seen by a build; the configuration register (`P15-TSK-031`) carries that floor. The broker posture
the clients rely on is [`infra/kafka/broker.properties`](../../infra/kafka/broker.properties): no
auto-creation, no unclean leader election, three replicas with two in sync, SCRAM-SHA-512 only, and
nothing permitted that no ACL grants. `P15-TSK-015` provisions both files on the managed brokers and
`P15-TSK-012` on its test-only `kind` broker (one node: replication 1, nothing else changed).

**Off loopback the clients speak `SASL_SSL` with SCRAM-SHA-512** (owner decision C11).
`KafkaTransportGuard` refuses to start when a bootstrap off this machine is reached by any protocol
but `SASL_SSL` — `PLAINTEXT`, `SASL_PLAINTEXT` and `SSL` alike — when a SASL protocol has no
credential, when the credential is the marked local default, and when the protocol is one Kafka does
not define. `BrokerConnection` renders both clients' connection settings once: the SCRAM login
(mechanism fixed in code, so `PLAIN` cannot be chosen), hostname verification fixed on, and a PEM CA
bundle as the truststore when one is configured (`finapp.kafka.ssl.truststore-location`; otherwise
the JVM's trust store). The credential is `FINAPP_KAFKA_SASL_USERNAME` and
`FINAPP_KAFKA_SASL_PASSWORD` (`SECRET_MANAGEMENT.md`). Each principal's ACLs are data in the
catalogue: the application may describe, read and write the catalogued topics and read the groups
`finapp.<module>`, and nothing more; the provisioner creates and configures but deletes nothing, *as
declared* - its cluster `Alter` is the permission that edits ACLs, so it could grant itself more at
run time, and who holds that credential is the operating answer.

*The review fix round (2026-11-08).* "On this machine" is read strictly: every bootstrap entry must
be `host:port` or `[IPv6]:port`, on loopback too, or startup is refused. kafka-clients 4.2.1 reads
`localhost://kafka.internal:9092` as `kafka.internal`, and the guard's first reading took the text
before the first colon, so a remote broker passed as local over `PLAINTEXT`. The database guards
share the parser and were open the same way, measured against the pinned driver: a `host`, `PGHOST`
or `service` query parameter overrides the URL's host, and an unbracketed IPv6 entry beside a
loopback one was dropped unread; each now leaves the URL unread, so they fail closed. **The guard's
limit:** it checks the *bootstrap* list only. After the first contact the clients connect to the
listeners the brokers advertise, which no guard sees; off loopback the admitted protocol is
`SASL_SSL` with hostname verification on every listener, but a broker on loopback that advertises a
remote listener would take the clients there under the loopback protocol. Only
`EventingBeans` may make a broker connection (`brokerConnectionsAreMadeBehindTheGuard`), so the
configuration the guard reads is the configuration that connects.

**A rolling update moves partitions without stopping a group.** Every replica runs every module's
consumer loop, so under the eager protocol every rolling step stopped every member of every group
while partitions were revoked and reassigned. The consumers now use the cooperative assignor alone
(`group.protocol=classic` pinned, so a client default cannot move the group to a protocol where
these settings are the broker's), a 30-second session timeout (a killed pod's partitions move within
half a minute; a pod that leaves gracefully says so at once), and `max.poll.records` 10 within
`max.poll.interval.ms` of 120 seconds. That interval is a **budget, not a bound** (reworded in the
review fix round): per record two waits are bounded — a pool wait (Hikari's `connection-timeout`,
5 s) and the inbox claim (0.5 s) — but the handler's own statements have no timeout, and the offset
commit can wait up to `default.api.timeout.ms` (60 s, written out). Twelve seconds a record leaves
6.5 for the statements after both bounded waits, with a prompt commit; a commit that ran its full
60 s leaves half a second. A batch that overruns is evicted and redelivered into the dedupe.
`InboxPollBudgetTest` computes this from the committed configuration. No eviction or commit-failure
series exists yet beyond the thrown polls' count below; `P15-TSK-025` carries one. No static
membership: a Deployment's pod names change on every rollout. **None of
this is the correctness argument.** A rebalance, cooperative or not, redelivers whatever was settled
and not yet committed, and the inbox deduplicates it; the assignor only keeps the other members
consuming meanwhile. `KafkaRebalanceKafkaTest` is written to show both on a real broker; it has not
run yet, and is owed to the exit's database pass (`P15-TST-002`).

**A refused consumer fails readiness, never liveness.** When the broker refuses a consumer loop —
a credential that does not authenticate, a missing topic or group permission — the receiver raises
`BrokerAccessRefusedException` (the client's class name only), the loop records it until the broker
is seen to admit it — a poll that delivered a record, or three clean polls in a row (a refused client
returns quiet polls between reconnection attempts, so one was no evidence: the review fix round) —
and the readiness contributor `kafkaConsumers` reports DOWN. That is the fault a release
with a wrong secret carries, so the rollout halts on it. A broker that is merely down is not a
refusal: its polls return empty and readiness is unaffected, because the outbox holds every event
until it returns. Nor is a **failed TLS handshake**, though the client files it under
authentication (the review fix round): a broker's expired or rotated certificate fails every
replica's handshake at once, and a refusal there would take the whole fleet out of readiness for a
fault no rollout carried. It is an ordinary failure — logged by class (`SslAuthenticationException`),
backed off, and counted with every thrown poll in `finapp.inbox.poll.failures{outcome}` (`refused`
or `failed`), the series an alert reads; the alert itself is `P15-TSK-025`'s routing. The cost: replicas share one credential, so revoking it before the fleet has
rolled onto its successor takes every replica's readiness down — rotation is add, roll, remove
(ADR-0123 decision 5; `OPERATIONS_RUNBOOK.md` §11.6).

## Schema evolution — *Implemented* (`P15-TSK-027`)

An event is a contract with a reader the writer never meets, and during a rollout that reader can be the previous
release. So no release may change an event in a way the previous release's consumers cannot read. That is enforced at
build time (ADR-0122 decision 6). There is no runtime schema registry while no consumer exists outside the monolith
(owner decision C11).

**Neither side is kept by hand.** What the code writes is read from the compiled code; the committed files in
[`docs/events/`](../events/event-catalogue.yaml) stand for the last release, and are replaced only by the release procedure
(`OPERATIONS_RUNBOOK.md` §11.1.1).

| File | What it holds |
|---|---|
| `event-catalogue.yaml` | The last release's event types, each version with its producer, and each field with its kind and whether every record carries it |
| `handler-catalogue.yaml` | The last release's handler set: `(group, topic, eventType, consumerName)` (§Adding a handler) |
| `release-record.yaml` | What the coming release records about a change the build cannot judge alone. Empty today |

**How the code is read.** `EventFlows` runs a small abstract interpretation over every production class, the classes
`EnvelopeProducers` and the monitor sweep read too: operand stack and locals, joined at branch targets. Many producers
build the envelope in a helper that takes the event type and the payload as parameters, and assemble the payload in the
caller, a ternary or a loop, so a value that depends on a parameter is followed to each call site, up the chain. Each
write to the outbox closes as producer, type, version and fields. A field records its name, whether every path to the
write sets it, and a kind read from how its value was made: `uuid`, `integer`, `decimal`, `boolean`, `date`, `currency`,
`enum(<class>)`, `literal(<a|b>)`, or `identifier` for any other string. A loop's numbered field (`"reasonCode" + (i + 1)`)
is `reasonCode#`, never required, and a field set inside a lambda (`Optional.ifPresent`) is not required either: the
lambda runs when something chooses to run it. **What the reader cannot read fails the build**: a guess would be a wrong contract and
silence would be a hole. `EventCatalogueTest` cross-checks it against `EnvelopeProducers`, a separate simulation of the
same construction sites.

**The rule: additive.** Compared against the last release, per event type:

| Change | Same `eventVersion` | The next one |
|---|---|---|
| A field added | Accepted | Accepted |
| A field removed | Refused | Refused |
| A kind changed | Refused, unless an `identifier` is narrowed to something more exact | Refused, likewise |
| A field every record carried, no longer always written | Refused | Refused |
| A value withdrawn from a `literal` | Refused | Refused |
| Another enumeration behind a field | Refused | Refused |
| A type no producer writes any more | Refused, unless the release record retires it | — |
| A type moved to another producer | Refused: its consumers subscribe to the first topic | Refused |
| A version going backwards | — | Refused |

An added field is optional for a reader on the previous release, which never looks at it (`EventPayload.fields` hands it
over unread). It is optional for a reader on the new release too, because a record written before it does not carry it:
when the catalogue is rolled forward, a field added under an existing version becomes `required: false`.

**What a version is.** An `eventVersion` is what a consumer opts into. `RiskSignalIntake` refuses a version it does not
know by throwing, which stalls its partition; the origination hints log it and act on nothing. So a new version of a
type some handler of the last release consumes is **refused until the release record names the release whose consumers
learned it**, and the consumer ships a release before the producer. Otherwise an additive change stays under the same
version where a consumer reads the type (`credit.CreditDecisionRecorded`), and may take the next one where a reader must
be able to tell old records from new (`merchant.FeeAssessed` version 2, `crossborder.CrossBorderPaymentReturned` version
2; neither has a consumer). A change that cannot be additive is a new event type, which consumers opt into by adding a
handler, with the old type retired through the release record. A version the code stops writing stays in the catalogue:
its records are on the wire until retention passes them, and a replay or a rollback must parse them. `RiskSignalsReadTheCatalogueTest` runs the one
consumer that reads a payload, `SignalMappings`, over the released shape and the current shape of everything it reads,
each with a field it has never seen.

**`required` is per event type, and that has a limit.** A shared helper that writes a field on some paths makes it
`required: false` for every type it serves, even a type that never takes the other path. `SignalMappings` needs four such
fields: `from` on `crossborder.BeneficiaryActivated` and `BeneficiaryBlocked`, and `previousBucket` on
`lending.LoanDelinquencyChanged` and `bnpl.BnplDelinquencyChanged`. The catalogue cannot say those types always carry
them. The test pins exactly that set, so a consumer newly leaning on an optional field has to be argued for.

**Amounts.** Every `<name>Minor` field is an integer of minor units, beside an explicit currency and scale
(`DOD-EVENT`), and no field anywhere is written from a floating-point or decimal value. The rule rests on the naming
convention: an amount named otherwise is not seen as one.

**One version per payments type** (the Phase 7 residue #49). Every payments event took its version from one constant, so
a change to one type's contract would have moved every other type's version on the wire. Each construction site now
passes its own type to `PaymentEventVersions.of`, and the catalogue's test refuses a site naming another type's version
or a bare number. Every number is still the one already on the wire. Other modules still share one constant across some
of their types; a bump there is seen per type by the catalogue, and the rule above still applies to each.

**The limits, said plainly.** A field that keeps its name and its kind but starts to carry a different quantity is
invisible to any reading of the code: that is a reviewer and a new version. A new constant in an enumeration behind a
field is not refused, so a reader must not `valueOf` one blindly. The committed baseline is only as good as its review:
rolling it in the same change that breaks it passes, exactly as regenerating `docs/api/openapi.json` would.

## Adding a handler — *Implemented* (`P15-TSK-027`)

**Payload compatibility does not cover a new handler.** `KafkaEventReceiver` acknowledges a record no registered handler
wants (`UNHANDLED`). Groups are per module, and each topic carries every type its module produces. So while release N − 1
and release N share a group, an N − 1 replica commits unseen a record of a type only N handles; and after a rollback,
every such record is committed unseen. **A handler removed in N opens the same hole from the other side**: N acknowledges
the type unseen, and a rollback to N − 1 never gets those records back.

So the handler set is a contract too. `HandlerSetCatalogueTest` reads it from the code, by invoking every `@Bean` method
that returns an `InboxEventHandler` with collaborators that do nothing and asking each handler its identity, with the
group from `InboxConsumers`' own derivation, and diffs it against the last release. A change is refused unless
`release-record.yaml` records a safe way through:

| Change | What the release must record |
|---|---|
| A handler added to a group the last release had | `replay` of its event type over `[rollout, rollback]`: after the rollout completes, through the audited replay, the inbox deduplicating what N already applied |
| A handler in a new group | `newGroup` with `startingOffsets`: `latest`, an instant, or `earliest` only if `naturallyIdempotent: true`, because `earliest` replays the whole retention |
| A handler removed | `replay` over `[rollback]` |
| A consumer renamed | Both of the above: it is a removal and an addition, and a new name makes every past delivery look unhandled |

A step recorded for a handler that did not change is refused too, so a stale record cannot pass for the next release's.
The same test holds every handler's topic to `infra/kafka/topics.yaml` and refuses a type that topic never carries: a
handler naming either would subscribe to nothing, silently.

**A new group needs its offsets set before its first start**, because the consumers' `auto.offset.reset` is `earliest`
(`OPERATIONS_RUNBOOK.md` §11.1.1).

**What the build proves is that the release recorded the step**, not that the step was run. The replay's own audit record
is that evidence, and `P15-TSK-012`'s handler-window case rehearses it: none lost, none applied twice.

## Delivery Assumptions

Consumers must tolerate:
- duplicate delivery
- delayed delivery
- out-of-order delivery where ordering is not guaranteed
- replay
- consumer restart
- rebalance — partition ownership moving while a record is in flight, so two instances can
  briefly hold the same delivery; the inbox primary key is the arbiter

**The inbox addresses duplication only.** Delay, reordering and replay remain the handler's
problem, and no dedupe table can solve them: a handler that would be wrong seeing
`TransferCompleted` before `TransferInitiated` is wrong whether or not it deduplicates. The
answer is an order-independent handler or an explicit ordering key. This is stated because a
dedupe wrapper is precisely the component people later assume solved ordering too.

Do not rely on event delivery alone to enforce accounting invariants.
