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

## Delivery Assumptions

Consumers must tolerate:
- duplicate delivery
- delayed delivery
- out-of-order delivery where ordering is not guaranteed
- replay
- consumer restart

Do not rely on event delivery alone to enforce accounting invariants.
