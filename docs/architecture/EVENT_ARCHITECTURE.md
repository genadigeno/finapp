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
that genuinely needs a nested object, a monetary amount or a list needs the wire-format decision
taken, not worked around — at which point `EventPayload` is replaced rather than extended.

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
self-healing: the aggregate stays stalled until a person acts. There is no tooling yet — Phase
15 owns dead-letter handling and replay — so the procedure is manual and deliberately small.

An abandoned row announces itself twice: an `ERROR` naming the event, the aggregate and the
attempt count, and a `WARN` on every subsequent cycle saying the aggregate is blocked behind it.
Both carry the originating flow's correlation identifier, so the event can be traced back to the
transfer or payment that produced it.

```sql
-- What is abandoned, and what is stuck behind it.
SELECT event_id, aggregate_id, event_type, attempts, dead_lettered_at, last_error
FROM platform.outbox_event
WHERE dead_lettered_at IS NOT NULL
ORDER BY dead_lettered_at;
```

Two resolutions, and the choice is a judgement about the event, never a default:

- **Retry it** — the cause was environmental (a broker misconfiguration, an expired credential,
  a topic that did not exist) and the event is still correct to publish. Clear the abandonment
  and let the relay pick it up again:
  `UPDATE platform.outbox_event SET dead_lettered_at = NULL, attempts = 0, next_attempt_at = now() WHERE event_id = ?`
- **Abandon it permanently** — the event is genuinely unpublishable. This is a decision that
  consumers will never see a fact that happened, so it needs the same scrutiny as a manual
  adjustment: record why, and expect to answer for it at reconciliation. The row is **not**
  deleted; it is the evidence that the gap exists.

**Never resolve an abandoned row by deleting it.** The row is the only record that an event
which should have been published was not, and the outbox is where that question is answered.

Both statements are manual `UPDATE`s against a table the application role can write, which is
acceptable only because the outbox is transport rather than financial history (`INV-EVT-02`,
`V005`). The same action against a ledger table would not be.

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
retention is sized against.

**The shell acknowledges only what has committed** (`P2-TSK-002`). `KafkaEventReceiver` commits
the broker offset strictly after the inbox transaction commits, with auto-commit disabled; the
two commits cannot be atomic, and every failure between them — crash, rebalance, lost connection
— resolves as a redelivery into the dedupe, the safe direction. A record that cannot be handled
is **seeked back to rather than skipped**: acknowledging past it would be an undetectable gap,
the relay's block-don't-skip rule on the consuming side. Consumer-group offsets are transport
bookkeeping, never truth (`DISTRIBUTED_EXECUTION.md` §3): losing them replays the topic and the
inbox absorbs it.

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
