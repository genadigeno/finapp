-- Dead letters and replay (P15-TSK-028).
--
-- Three things, each the record of a decision the platform previously left to a manual UPDATE or to a stalled partition.
--
--   1. An abandoned outbox event can be DISCARDED: a two-person decision that consumers will never see a fact. The row
--      stays, because it is the only evidence that the gap exists; a discard is never a deletion.
--   2. A poison inbox record can be PARKED: its bounded retries spent for a reason of its own, set aside so its partition
--      goes on. platform.inbox_parked_record is the durable count, because a count in memory resets on every restart and
--      means nothing across N instances.
--   3. A published event can be REPLAYED: requeued for the relay, and safe only while the dedupe rows that refuse the
--      duplicate still exist when the replayed record arrives.
--
-- ROLLING UPDATES
--   Every constraint below is on a column this migration adds, so release N - 1, which never writes those columns, can no
--   more violate one than it could before. A discarded row KEEPS dead_lettered_at, deliberately: an N - 1 relay reads it as
--   abandoned and stops, the safe direction; clearing it would have N - 1 publish an event two people decided nobody should
--   see. A replayed row is an ordinary pending row to N - 1, which publishes it correctly.

ALTER TABLE platform.outbox_event
    -- Set when two people decided this event will never be published (dead_letter_discard). The server's clock (ADR-0014).
    ADD COLUMN discarded_at       TIMESTAMPTZ,

    -- The event's first publication, kept across replays: published_at goes back to NULL so the relay does the publishing,
    -- and a replay's time window must not move under the operator's feet.
    ADD COLUMN first_published_at TIMESTAMPTZ,

    -- When a replay of this row was last ordered: the instant every consumer's dedupe row was last extended from, and so the
    -- dedupe window's anchor once there has been one (occurred_at before that, never published_at: an attempt whose
    -- acknowledgement was lost reached the broker and left no mark).
    ADD COLUMN replay_ordered_at  TIMESTAMPTZ;

-- NOT VALID, because a validating ADD CONSTRAINT scans this table under ACCESS EXCLUSIVE and every money-moving writer inserts
-- here. Nothing is lost: every existing row holds NULL in the columns just added, so no row can violate them, and a NOT VALID
-- constraint is enforced on every INSERT and UPDATE from now on. VALIDATE would only set a catalogue flag nothing reads; the next
-- platform migration that wants it may run it, under the SHARE UPDATE EXCLUSIVE lock that does not stop a writer.
ALTER TABLE platform.outbox_event
    -- Only an abandoned event can be discarded, and a retry cannot resurrect one: clearing dead_lettered_at on a discarded
    -- row violates this.
    ADD CONSTRAINT outbox_event_discarded_implies_abandoned
        CHECK (discarded_at IS NULL OR dead_lettered_at IS NOT NULL) NOT VALID,

    -- A row consumers will never see cannot also have been published.
    ADD CONSTRAINT outbox_event_not_both_published_and_discarded
        CHECK (discarded_at IS NULL OR published_at IS NULL) NOT VALID,

    ADD CONSTRAINT outbox_event_first_published_implies_replayed
        CHECK (first_published_at IS NULL OR replay_ordered_at IS NOT NULL) NOT VALID;

COMMENT ON COLUMN platform.outbox_event.discarded_at IS
    'Set by a four-eyes decision that this event will never be published. The row is kept: it is the evidence of the gap.';

COMMENT ON COLUMN platform.outbox_event.first_published_at IS
    'The first publication instant, kept across replays. NULL until the row is first replayed.';

COMMENT ON COLUMN platform.outbox_event.replay_ordered_at IS
    'When a replay was last ordered, on the server''s clock: the anchor of the dedupe window for a replayed row.';

-- The inbox's parked state. The failure ledger of one consumer's record, before and after it parks.
--
-- platform.inbox_message stays what V007 made it: the row exists if and only if the effect happened. A failed attempt has no
-- effect, so its count cannot live there; and a parked state in that table would need the sweep and every reader to know
-- about a row that means the opposite of the rest. A table of its own has no sweep.
CREATE TABLE platform.inbox_parked_record
(
    -- The door's handle: (consumer, dedupe_key) carries dots and colons that do not belong in a path.
    park_id          UUID        NOT NULL,

    -- The same key as inbox_message. For a record that could not be parsed, consumer is the group and dedupe_key is
    -- 'malformed:<topic>:<partition>:<offset>': nothing else about it can be read.
    consumer         TEXT        NOT NULL,
    dedupe_key       TEXT        NOT NULL,

    -- The counted dimension of finapp.deadletter.parked, and where to go looking.
    consumer_group   TEXT        NOT NULL,
    message_type     TEXT        NOT NULL,
    correlation_id   TEXT        NOT NULL,
    topic            TEXT        NOT NULL,
    partition_number INTEGER     NOT NULL,
    record_offset    BIGINT      NOT NULL,

    state            TEXT        NOT NULL,

    -- Failures of the record's own; a transient one is never counted (InboxFailures). Durable, because N instances and
    -- restarts must all see one count.
    attempts         INTEGER     NOT NULL,

    -- The failure's class name and nothing else: a message can carry a host, a statement or row content.
    last_failure     TEXT        NOT NULL,

    -- The server's clock (ADR-0014): the elapsed floor of the retry budget is judged across instances.
    first_failed_at  TIMESTAMPTZ NOT NULL,
    last_failed_at   TIMESTAMPTZ NOT NULL,
    parked_at        TIMESTAMPTZ,
    settled_at       TIMESTAMPTZ,

    CONSTRAINT inbox_parked_record_pk PRIMARY KEY (consumer, dedupe_key),
    CONSTRAINT inbox_parked_record_park_id_unique UNIQUE (park_id),

    CONSTRAINT inbox_parked_record_state_known
        CHECK (state IN ('FAILING', 'PARKED', 'HANDLED', 'DISCARDED')),
    CONSTRAINT inbox_parked_record_attempts_not_negative
        CHECK (attempts >= 0),
    CONSTRAINT inbox_parked_record_parked_has_instant
        CHECK (state <> 'PARKED' OR parked_at IS NOT NULL),
    CONSTRAINT inbox_parked_record_settled_has_instant
        CHECK (state NOT IN ('HANDLED', 'DISCARDED') OR settled_at IS NOT NULL),

    CONSTRAINT inbox_parked_record_consumer_bounded
        CHECK (length(consumer) BETWEEN 1 AND 200),
    CONSTRAINT inbox_parked_record_dedupe_key_bounded
        CHECK (length(dedupe_key) BETWEEN 1 AND 200),
    CONSTRAINT inbox_parked_record_group_bounded
        CHECK (length(consumer_group) BETWEEN 1 AND 200),
    CONSTRAINT inbox_parked_record_type_bounded
        CHECK (length(message_type) BETWEEN 1 AND 200),
    CONSTRAINT inbox_parked_record_correlation_bounded
        CHECK (length(correlation_id) BETWEEN 1 AND 128),
    CONSTRAINT inbox_parked_record_topic_bounded
        CHECK (length(topic) BETWEEN 1 AND 255),
    CONSTRAINT inbox_parked_record_failure_bounded
        CHECK (length(last_failure) BETWEEN 1 AND 200)
);

COMMENT ON TABLE platform.inbox_parked_record IS
    'The durable failure count of a consumer''s record and, once parked, the record of it. Never the payload, which the outbox holds.';

-- Serves the gauge and the door's list: only the few parked rows are in it.
CREATE INDEX inbox_parked_record_parked
    ON platform.inbox_parked_record (consumer_group, parked_at)
    WHERE state = 'PARKED';

-- A discard, proposed by one person and decided by another, over an abandoned event or a parked record.
--
-- The INV-AUD-04 shape: two acts, each with one actor, and the pairing is a row here. Compared by the person's identifier
-- only, never by an actor type, which an operator's session and a customer's differ on for the same person.
CREATE TABLE platform.dead_letter_discard
(
    discard_id      UUID        NOT NULL,
    subject_kind    TEXT        NOT NULL,

    -- The outbox event_id, or the inbox park_id.
    subject_id      UUID        NOT NULL,

    status          TEXT        NOT NULL,
    proposed_by     TEXT        NOT NULL,
    proposed_at     TIMESTAMPTZ NOT NULL,
    proposal_reason TEXT        NOT NULL,
    decided_by      TEXT,
    decided_at      TIMESTAMPTZ,
    decision_reason TEXT,

    CONSTRAINT dead_letter_discard_pk PRIMARY KEY (discard_id),

    CONSTRAINT dead_letter_discard_kind_known
        CHECK (subject_kind IN ('OUTBOX_EVENT', 'INBOX_RECORD')),
    CONSTRAINT dead_letter_discard_status_known
        CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),

    -- A decision is either wholly absent or wholly recorded.
    CONSTRAINT dead_letter_discard_decision_complete
        CHECK ((status = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL)
            OR (status <> 'PROPOSED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL)),

    -- INV-AUD-04: the second person. A rejection may be the proposer's own, withdrawing it; an approval never is.
    CONSTRAINT dead_letter_discard_four_eyes
        CHECK (status <> 'APPROVED' OR decided_by <> proposed_by),

    CONSTRAINT dead_letter_discard_person_bounded
        CHECK (length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR length(decided_by) BETWEEN 1 AND 200)),
    CONSTRAINT dead_letter_discard_reason_bounded
        CHECK (length(proposal_reason) BETWEEN 1 AND 1000
            AND (decision_reason IS NULL OR length(decision_reason) BETWEEN 1 AND 1000))
);

COMMENT ON TABLE platform.dead_letter_discard IS
    'The four-eyes decision that an abandoned event or a parked record will never be delivered. Never deleted.';

-- One standing or accepted decision per subject. The door locks the subject first, so this is the backstop, not the mechanism.
CREATE UNIQUE INDEX dead_letter_discard_one_standing
    ON platform.dead_letter_discard (subject_kind, subject_id)
    WHERE status IN ('PROPOSED', 'APPROVED');

-- The application holds UPDATE on this table, so the machine is the database's, for every writer (the platform V003 and ledger
-- V010 convention): PROPOSED goes to a decision once, a decision is final, and what was proposed is never rewritten.
CREATE FUNCTION platform.dead_letter_discard_permits_only_decision()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'a discard decision is never truncated (P15-TSK-028, INV-AUD-04)';
    END IF;
    IF OLD.status <> 'PROPOSED' OR NEW.status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'a discard''s only edges are PROPOSED -> APPROVED and PROPOSED -> REJECTED (P15-TSK-028, INV-AUD-04)';
    END IF;
    IF NEW.discard_id <> OLD.discard_id OR NEW.subject_kind <> OLD.subject_kind OR NEW.subject_id <> OLD.subject_id
            OR NEW.proposed_by <> OLD.proposed_by OR NEW.proposed_at <> OLD.proposed_at OR NEW.proposal_reason <> OLD.proposal_reason THEN
        RAISE EXCEPTION 'what was proposed is never rewritten (P15-TSK-028, INV-AUD-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dead_letter_discard_permits_only_decision
    BEFORE UPDATE ON platform.dead_letter_discard
    FOR EACH ROW
    EXECUTE FUNCTION platform.dead_letter_discard_permits_only_decision();

CREATE TRIGGER dead_letter_discard_is_never_truncated
    BEFORE TRUNCATE ON platform.dead_letter_discard
    FOR EACH STATEMENT
    EXECUTE FUNCTION platform.dead_letter_discard_permits_only_decision();

-- The grants: no DELETE on either table, because neither is ever deleted from.
GRANT SELECT, INSERT, UPDATE ON platform.inbox_parked_record TO finapp_app;
GRANT SELECT, INSERT, UPDATE ON platform.dead_letter_discard TO finapp_app;

-- One column of inbox_message, and only that: a replay extends the life of the dedupe rows it depends on. V008 gave this table
-- no UPDATE because there was nothing to update, and V007's "row exists if and only if the effect happened" is untouched: the
-- consumer, the key and the instant it was processed stay immutable to the application.
GRANT UPDATE (expires_at) ON platform.inbox_message TO finapp_app;
