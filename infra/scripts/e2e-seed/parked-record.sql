-- TEST-ONLY (P17-TSK-106): a parked inbox record the Operator Console can release, or discard by four-eyes, planted on a test
-- database by infra/scripts/e2e-seed.sh and by nothing else. Never a migration, never in an image (E2eSeedStaysTestOnlyTest).
--
-- Two rows, in one statement so psql and JDBC run the same text:
--   1. a PUBLISHED outbox event, occurred now: what a release republishes. The door releases only a record whose dedupe key is an
--      outbox event that is published and inside the dedupe window (DeadLetterOperations.release); a malformed record's key
--      ('malformed:...') is refused as NOT_RELEASABLE, which is why the kind rehearsal's poison record cannot stand in for this;
--   2. the inbox record its consumer parked: state PARKED, its budget spent - five failures over two minutes, the application's
--      finapp.inbox.park-after-* defaults - as InboxParking leaves one (JdbcInboxParking).
--
-- The consumer and its group are named for what they are, e2e.seed-consumer in finapp.e2e-seed, so the parked gauge and its page
-- (InboxDeadLetterParked) say whose record it is. The record was never on the broker: record_offset -1 says so; no door reads it.
--
-- Why the fleet leaves it alone until a person acts (the multi-instance question): a parked row changes only on its own consumer's
-- next failure of the same (consumer, dedupe key), and no consumer of that name exists; the published event is kept from the
-- retention sweep by this very row (sweep_published_outbox_events keeps rows a standing parked record names) and is inside the bound
-- anyway.
--
-- Why a release is harmless: it requeues the event, the relay publishes it on finapp.reconciliation, and its type, e2e.SeededEvent,
-- is one no handler reads - every consumer group answers it UNHANDLED (KafkaEventReceiver). The released row stays FAILING, since
-- no consumer of its name will ever settle it, and keeps its event from the sweep: a few bytes on a test database, by design.
--
-- Prints the record's park_id (the door's handle) and the event it names.
WITH seed AS (
    SELECT 'e2e-seed-' || gen_random_uuid() AS correlation
), event AS (
    INSERT INTO platform.outbox_event (
        event_id, event_type, event_version, schema_version, aggregate_id, aggregate_type, occurred_at, producer,
        correlation_id, causation_id, payload, payload_media_type,
        published_at, attempts, next_attempt_at)
    SELECT uuidv7(), 'e2e.SeededEvent', 1, 1, uuidv7(), 'e2e.Seed', now(), 'reconciliation',
           seed.correlation, seed.correlation, convert_to('{"seededBy":"infra/scripts/e2e-seed.sh"}', 'UTF8'), 'application/json',
           now(), 1, now()
      FROM seed
    RETURNING event_id, event_type, correlation_id
), parked AS (
    INSERT INTO platform.inbox_parked_record (
        park_id, consumer, dedupe_key, consumer_group, message_type, correlation_id, topic, partition_number, record_offset,
        state, attempts, last_failure, first_failed_at, last_failed_at, parked_at)
    SELECT gen_random_uuid(), 'e2e.seed-consumer', event.event_id::text, 'finapp.e2e-seed', event.event_type, event.correlation_id,
           'finapp.reconciliation', 0, -1,
           'PARKED', 5, 'E2eSeedPlantedFailure', now() - INTERVAL '2 minutes', now(), now()
      FROM event
    RETURNING park_id, dedupe_key
)
SELECT park_id, dedupe_key AS event_id FROM parked;
