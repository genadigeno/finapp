-- TEST-ONLY (P17-TSK-106): an abandoned outbox event for the Operator Console's end-to-end tests, planted on a test database by
-- infra/scripts/e2e-seed.sh and by nothing else. Never a migration, never in an image (E2eSeedStaysTestOnlyTest).
--
-- The state no door reaches: an hour of failed publishes is not something a test can wait for, so the row is written as the relay
-- leaves one it gave up on (OutboxRelay's MARK_FAILED_SQL at its limit): unpublished, attempted as often as RetryPolicy.DEFAULT
-- allows, dead_lettered_at set, neither discarded nor replayed. The doors then act on it as on any other: a retry, or a discard
-- proposed by one person and approved by another (DeadLetterOperations).
--
-- Why the fleet leaves it alone until a person acts (the multi-instance question): every relay's candidate query takes an
-- aggregate only when its head row has dead_lettered_at NULL, and this aggregate is fresh and holds this one row; the retention
-- sweep deletes published rows only. Nothing but a door changes it.
--
-- Why publishing it later is harmless: a retry returns it to the relay, which publishes it on finapp.reconciliation, a declared
-- topic (infra/kafka/topics.yaml). Its type, e2e.SeededEvent, is a type no handler reads, so every consumer group answers it
-- UNHANDLED and moves on (KafkaEventReceiver): no effect anywhere, no money touched.
--
-- One statement, so psql and JDBC run the same text. Prints the event's identifier.
INSERT INTO platform.outbox_event (
    event_id, event_type, event_version, schema_version, aggregate_id, aggregate_type, occurred_at, producer,
    correlation_id, causation_id, payload, payload_media_type,
    published_at, attempts, next_attempt_at, dead_lettered_at, last_error)
SELECT uuidv7(), 'e2e.SeededEvent', 1, 1, uuidv7(), 'e2e.Seed', now(), 'reconciliation',
       seed.correlation, seed.correlation, convert_to('{"seededBy":"infra/scripts/e2e-seed.sh"}', 'UTF8'), 'application/json',
       NULL, 14, now(), now(), 'e2e seed: planted as abandoned; no publication was attempted'
  FROM (SELECT 'e2e-seed-' || gen_random_uuid() AS correlation) AS seed
RETURNING event_id;
