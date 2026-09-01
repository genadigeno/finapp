-- The transactional outbox: a publication record written with the fact it describes.
--
-- INVARIANTS THIS MIGRATION ENFORCES
--   INV-EVT-01  A domain fact and its publication record are written in the same transaction.
--               The table is the "publication record" half; the writer never opens a
--               transaction of its own, so a rolled-back fact takes its outbox row with it.
--   INV-EVT-03  Every published event carries the full envelope. All ten fields are columns
--               here and all ten are NOT NULL, so an untraceable event cannot be queued any
--               more than it can be constructed.
--   INV-EVT-02  Kafka is transport, not truth. Nothing reads a balance or a position from
--               this table; it holds what to publish, not what is true.
--
-- WHY A TABLE AND NOT A DIRECT PUBLISH
--   ADR-0005. Publishing inside the transaction cannot work - the broker has no idea whether
--   the transaction will commit - and publishing after it opens a window in which the process
--   dies having committed a fact nobody will ever hear about. The outbox moves the problem to
--   one the database already solves: the row and the fact commit together, and a separate
--   relay (P0-TSK-020) takes it from there.
--
-- MUTABILITY
--   Rows are inserted by the writer and updated exactly once by the relay, to record
--   publication. They are not financial history: an outbox row records that something must be
--   announced, never what is true, so INV-HIST-01 does not apply and the row may be deleted
--   once published and retained long enough for diagnosis.
--
--   Required grants, applied by P0-TSK-022 when the roles exist (DATA_MIGRATIONS.md §6):
--       migrator     - owner; DDL only
--       application  - SELECT, INSERT, UPDATE, DELETE

CREATE TABLE platform.outbox_event
(
    -- The envelope's own identifier is the key. It is a UUIDv7, so the table is written in
    -- roughly ascending order and the index appends rather than scattering (ADR-0013) - which
    -- matters more here than almost anywhere, because an outbox is pure insert traffic.
    --
    -- It is also the value a consumer's inbox will deduplicate on (INV-IDEM-04), so it must be
    -- the same identifier that was minted with the event, never regenerated on publication.
    event_id           UUID        NOT NULL,

    -- The envelope, all ten fields, all mandatory (INV-EVT-03).
    event_type         TEXT        NOT NULL,
    event_version      INTEGER     NOT NULL,
    schema_version     INTEGER     NOT NULL,
    aggregate_id       UUID        NOT NULL,
    aggregate_type     TEXT        NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL,
    producer           TEXT        NOT NULL,
    correlation_id     TEXT        NOT NULL,
    causation_id       TEXT        NOT NULL,

    -- The event's own data, opaque here. The envelope carries no payload precisely so that a
    -- relay can route and publish a row without deserialising anything domain-specific; this
    -- column is that payload, and nothing in the platform interprets it.
    payload            BYTEA       NOT NULL,
    payload_media_type TEXT        NOT NULL,

    -- Publication state. NULL means not yet published, and that is the whole query the relay
    -- runs.
    published_at       TIMESTAMPTZ,
    attempts           INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT outbox_event_pk PRIMARY KEY (event_id),

    CONSTRAINT outbox_event_type_bounded
        CHECK (length(event_type) BETWEEN 1 AND 200),
    CONSTRAINT outbox_event_aggregate_type_bounded
        CHECK (length(aggregate_type) BETWEEN 1 AND 200),
    CONSTRAINT outbox_event_producer_bounded
        CHECK (length(producer) BETWEEN 1 AND 200),
    CONSTRAINT outbox_event_media_type_bounded
        CHECK (length(payload_media_type) BETWEEN 1 AND 200),
    CONSTRAINT outbox_event_correlation_bounded
        CHECK (length(correlation_id) BETWEEN 1 AND 128),
    CONSTRAINT outbox_event_causation_bounded
        CHECK (length(causation_id) BETWEEN 1 AND 128),

    -- Zero is what an unset integer looks like, so a row that failed to carry a version would
    -- otherwise be indistinguishable from one that carries version zero.
    CONSTRAINT outbox_event_versions_positive
        CHECK (event_version >= 1 AND schema_version >= 1),

    CONSTRAINT outbox_event_attempts_not_negative
        CHECK (attempts >= 0),

    -- A published row has been attempted at least once. The converse is not asserted: an
    -- attempted row may legitimately still be unpublished, which is exactly what a retry is.
    CONSTRAINT outbox_event_published_implies_attempted
        CHECK (published_at IS NULL OR attempts >= 1)
);

COMMENT ON TABLE platform.outbox_event IS
    'Events awaiting publication, written in the same transaction as the fact they describe '
    '(INV-EVT-01). Transport, never truth: no balance, position or decision is derived from '
    'this table (INV-EVT-02).';

COMMENT ON COLUMN platform.outbox_event.event_id IS
    'The envelope''s identifier, minted with the event. A consumer deduplicates on it, so '
    'regenerating it on publication would defeat every inbox downstream (INV-IDEM-04).';

COMMENT ON COLUMN platform.outbox_event.published_at IS
    'NULL means pending. The relay selects on this rather than on a sequence watermark - see '
    'the note below.';

-- THE RELAY SELECTS UNPUBLISHED ROWS, NEVER "EVERYTHING ABOVE A WATERMARK".
--
-- A sequence-watermark relay is the classic outbox bug. Sequence values are allocated when a
-- row is inserted but become visible when its transaction commits, so a slow transaction can
-- commit a LOWER id after a faster one has already committed a higher one. A relay that
-- remembers "I have processed up to 500" then skips that row permanently: the event is
-- committed, is never published, and nothing ever reports it. Selecting on published_at IS
-- NULL has no watermark to be wrong about.
--
-- Hence this index, and hence its predicate. Unlike the retention sweep V002 deliberately left
-- unindexed, the query here is fixed by ADR-0005 rather than guessed: the relay polls
-- unpublished rows in order. The index is partial, so it holds only the backlog and shrinks
-- back to nearly nothing as the relay keeps up - published rows leave it rather than
-- accumulating in it.
CREATE INDEX outbox_event_pending
    ON platform.outbox_event (event_id)
    WHERE published_at IS NULL;
