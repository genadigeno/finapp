-- The inbox: proof that a consumer has already handled a message.
--
-- INVARIANTS THIS MIGRATION ENFORCES
--   INV-IDEM-04  The same external event, webhook or message delivered any number of times
--                produces at most one financial effect. Enforced here by the primary key on
--                (consumer, dedupe_key), and only here: at-least-once delivery is the norm, so
--                a duplicate is an ordinary Tuesday rather than an incident, and the database
--                is the only component that can arbitrate between two instances receiving the
--                same redelivery at the same moment.
--   INV-EVT-04   Consumers tolerate duplication, delay, reordering and replay. This table is
--                the duplication half. Delay, reordering and replay remain the handler's
--                problem and no table can solve them - see the note on ordering below.
--
-- WHY THIS IS NOT platform.idempotency_record
--
--   The two look alike - a unique key that stops a second effect - and they are not the same
--   thing. Merging them would look like a simplification and would be a defect:
--
--     * The KEY comes from a different party. An idempotency key is chosen by a CLIENT for a
--       command it is issuing. A dedupe key is chosen by a PRODUCER for a message it already
--       sent. A client can be told its key was malformed; a producer cannot.
--
--     * There is no response to replay. An idempotency record stores the first caller's
--       response bytes because a retrying caller must receive exactly what the first one did.
--       Nobody is waiting on a redelivered message, so there is nothing to store and nothing
--       to return.
--
--     * There is no state machine, and adding one would be inventing a lifecycle with no
--       observer. An idempotency record needs IN_PROGRESS because a concurrent duplicate must
--       be told something. Here the row and the side effect commit together, so the row exists
--       if and only if the effect happened. There is no third state to be in.
--
--     * The key is scoped PER CONSUMER, which is the difference that matters most. One event
--       legitimately has many consumers, and each must process it exactly once. Keying on the
--       message alone would let whichever consumer got there first silently suppress every
--       other consumer - a bug that presents as "the notification never arrived" months later,
--       with the event visibly published and no error anywhere.
--
-- MUTABILITY
--   Rows are inserted and eventually deleted by the retention sweep. They are never updated:
--   there is no state to advance. This is not financial history (INV-HIST-01 does not apply) -
--   it records that a consumer handled a message, while what the handling DID is recorded by
--   whatever the handler wrote, in that same transaction.
--
--   Required grants, applied by P0-TSK-022 when the roles exist (DATA_MIGRATIONS.md §6):
--       migrator     - owner; DDL only
--       application  - SELECT, INSERT, DELETE
--   Note: no UPDATE. Unlike idempotency_record, nothing here is ever modified after insert, so
--   the narrower grant is the accurate one.

CREATE TABLE platform.inbox_message
(
    -- The consuming component. Part of the key, not a label: see the per-consumer note above.
    consumer       TEXT        NOT NULL,

    -- What identifies this message to this consumer. For a platform event it is the envelope's
    -- event_id, which P0-TSK-018 fixed at creation precisely so that a redelivery presents the
    -- same value rather than a freshly minted one. For a webhook it is whatever the provider
    -- guarantees is stable across its own retries - which is a per-provider question, and one
    -- the adapter answers rather than this table.
    dedupe_key     TEXT        NOT NULL,

    -- Diagnosis only. Never part of the key: a producer that changed an event's type would
    -- otherwise be able to re-deliver the same message past the dedupe.
    message_type   TEXT        NOT NULL,

    -- The flow this message belongs to, carried from the envelope. Without it a consumer's
    -- work cannot be joined to the transfer or payment that caused it, which is the whole
    -- question anyone has when tracing a duplicate.
    correlation_id TEXT        NOT NULL,

    -- When this consumer handled it. Application-supplied from one injected Clock (P0-TSK-013);
    -- the schema declares no DEFAULT, so no row can acquire a timestamp by accident.
    processed_at   TIMESTAMPTZ NOT NULL,

    -- When the retention sweep may delete this row.
    --
    -- SET BY THE SERVER, from a retention DURATION the caller chooses. The policy is the
    -- caller's - different producers have different redelivery windows - but the instant is a
    -- coordination boundary between the writer and a sweeper running on another instance, and
    -- ADR-0014 is explicit that those use the clock every instance shares. An instance whose
    -- clock ran slow would otherwise write an expiry already in the past and have its own
    -- dedupe record swept minutes later, admitting the duplicate this table exists to refuse.
    expires_at     TIMESTAMPTZ NOT NULL,

    -- The natural key IS the primary key, as in idempotency_record. Every lookup is by
    -- (consumer, dedupe_key), nothing references this table, and a surrogate id would add a
    -- second index that only ever costs writes.
    CONSTRAINT inbox_message_pk PRIMARY KEY (consumer, dedupe_key),

    CONSTRAINT inbox_message_consumer_bounded
        CHECK (length(consumer) BETWEEN 1 AND 200),
    CONSTRAINT inbox_message_dedupe_key_bounded
        CHECK (length(dedupe_key) BETWEEN 1 AND 200),
    CONSTRAINT inbox_message_type_bounded
        CHECK (length(message_type) BETWEEN 1 AND 200),
    CONSTRAINT inbox_message_correlation_bounded
        CHECK (length(correlation_id) BETWEEN 1 AND 128),

    -- A row that expires before it was processed would be swept immediately, so the record
    -- would exist for less time than it takes to be useful. That is a caller passing a
    -- nonsensical retention, and it should fail loudly at the moment it happens rather than
    -- present later as an unexplained duplicate.
    CONSTRAINT inbox_message_expires_after_processing
        CHECK (expires_at > processed_at)
);

COMMENT ON TABLE platform.inbox_message IS
    'Messages a consumer has already handled (INV-IDEM-04). Written in the same transaction as '
    'the side effect, so the row exists if and only if the effect happened.';

COMMENT ON COLUMN platform.inbox_message.consumer IS
    'Part of the key. One message legitimately has many consumers and each must process it '
    'once; keying on the message alone would let one consumer suppress the others.';

COMMENT ON COLUMN platform.inbox_message.expires_at IS
    'Set by the server clock from a caller-chosen duration (ADR-0014). Deleting a row early '
    'admits a duplicate, so the comparison must not cross two instances'' clocks.';

-- ORDERING IS NOT SOLVED HERE, AND CANNOT BE.
--
-- INV-EVT-04 also requires consumers to tolerate delay, reordering and replay. This table
-- addresses duplication only. A handler that would be wrong if it saw TransferCompleted before
-- TransferInitiated is wrong whether or not it deduplicates, and the fix is an order-independent
-- handler or an explicit ordering key - never a bigger inbox. Recorded here because a dedupe
-- table is exactly the thing people later assume solved ordering too.
--
-- NO INDEX ON expires_at, for the same reason as V002.
--
-- The sweep's predicate is not written yet, and a plain btree, a BRIN and a partial index have
-- different write costs on a table whose entire traffic is inserts. An index maintained on
-- every insert for a query nobody has written is a cost with no reader. The task that writes
-- the sweep adds the index its own query needs.
