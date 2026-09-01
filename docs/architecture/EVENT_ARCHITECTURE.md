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

## Delivery Assumptions

Consumers must tolerate:
- duplicate delivery
- delayed delivery
- out-of-order delivery where ordering is not guaranteed
- replay
- consumer restart

Do not rely on event delivery alone to enforce accounting invariants.
