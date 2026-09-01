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

**The transport adapter is deliberately absent.** The relay publishes through an
`EventPublisher` port; writing an adapter decides the wire format, the topic scheme and the
producer's acknowledgement configuration, and puts a broker client on the classpath. Those
belong with the phase that has events to publish.

## Delivery Assumptions

Consumers must tolerate:
- duplicate delivery
- delayed delivery
- out-of-order delivery where ordering is not guaranteed
- replay
- consumer restart

Do not rely on event delivery alone to enforce accounting invariants.
