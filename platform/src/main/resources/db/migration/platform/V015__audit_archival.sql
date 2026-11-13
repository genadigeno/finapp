-- finapp-contract: first-conforming-release=ANY; waives=TRIGGER; reason=the trigger added to platform.audit_record refuses no write any release makes: it queues each new record's id for the archive in the inserting transaction, and its one insert can fail only where the record's own insert has already failed, the id being that table's primary key
--
-- The audit archival (P16-TSK-010; THREAT_MODEL.md section 6 item 18; DATA_RETENTION.md section 3; ADR-0135).
--
-- WHAT THIS IS
--   A verified copy of every audit record, outside this database, made without deleting, altering or reordering one row of
--   platform.audit_record (INV-HIST-03, ADR-0010). The table keeps every row; nothing here deletes from it, and the application
--   role still holds INSERT and SELECT on it and nothing else.
--
-- THE UNIT IS A SEGMENT, NOT A PARTITION
--   DATA_RETENTION.md section 3 first designed the archive over closed monthly partitions. Partitioning is a separate decision,
--   taken by measurement and an ADR, and the archival is built without it. A committed audit row never changes, so it is "closed"
--   the moment it commits: a segment is a set of committed rows, fixed when it is claimed, and archived whole.
--
-- THE QUEUE: NO CURSOR, NO CLOCK
--   A cursor over audit ids or over occurred_at would skip, for good and with no signal, a row that committed after the cursor
--   passed its position: the ids are minted on instances' clocks and a transaction can commit long after it began. So every row is
--   queued instead, by a trigger in the transaction that writes it: platform.audit_archive_pending holds the id of every audit row
--   no segment holds yet. A row cannot commit without its queue entry, and the claim moves entries into a segment. Existing rows are
--   queued by this migration, after the trigger exists and while it holds the table's lock (CREATE TRIGGER takes SHARE ROW
--   EXCLUSIVE, which every insert waits behind until this migration commits), so no row is in neither place.
--
-- ONE SEGMENT IN FLIGHT, CHAINED
--   Every instance ticks. The claim locks the one head row FOR UPDATE SKIP LOCKED, so one claimer at a time; a contender does
--   nothing that tick (the source-join census's shape). Under it, a CLAIMED segment whose lease is live is left alone; one whose
--   lease has lapsed, on this database's clock, is taken over with a new holder token; with none, the next segment is cut from the
--   queue. A partial unique index allows one CLAIMED segment at most, and the member table's primary key one segment per row.
--   Segments are archived in order, each manifest naming its predecessor's digest, so an altered or missing object is detectable.
--
-- THE COPY IS THE DATABASE'S OWN TEXT
--   audit_archive_content(seq) is the segment's file: one line per row, a JSON array of every column in the table's order, its
--   timestamps in UTC to the microsecond, in audit id order, each ended by a newline. audit_archive_manifest(seq) is its manifest.
--   Both are pure functions of rows that never change and of the predecessor's digest, which is fixed before the segment can be
--   cut. So a taken-over segment writes the same bytes again, and audit_archive_record(line) reads a line back into a row.
--
-- VERIFY, THEN MARK
--   The application writes both objects, reads them back and compares them with these functions' text, and only then calls
--   complete_audit_archive_segment with the digests of what it read back. That function recomputes both digests from the rows and
--   refuses a mark whose digests are not the rows' (AADGS), or a caller that no longer holds the segment (AAHLD).
--
-- WHAT STOPS IT
--   * A column of platform.audit_record this archive was not reviewed against, or a changed type or order: a line would no longer
--     carry every column, so the claim and the mark refuse (55000) until a migration reviews it and a new format replaces format 1.
--   * A queued id with no audit row: someone deleted an audit record. The claim refuses (XX001) and nothing is cut over the gap.
--
-- THE GRANTS
--   The application role reads the four tables (the backlog gauges, the read path) and writes none of them: the claim, the mark
--   and the trigger are SECURITY DEFINER as this schema's owner, search_path pinned. Nothing here grants DELETE on anything.
--
-- THE TABLES ARE BUILT IN THIS TRANSACTION
--   The queue's backfill holds the audit table's lock until this migration commits, so every audited action waits on it: a pause at
--   today's size, acceptable only while no production data exists. A deployment with a large audit table adds the trigger first
--   and backfills in batches in a later release (DATA_MIGRATIONS.md section 4.4).

CREATE TABLE platform.audit_archive_head (
    head_id          smallint NOT NULL,
    last_segment_seq bigint   NOT NULL,

    CONSTRAINT audit_archive_head_pk PRIMARY KEY (head_id),
    CONSTRAINT audit_archive_head_is_one CHECK (head_id = 1),
    CONSTRAINT audit_archive_head_seq_counted CHECK (last_segment_seq >= 0)
);

COMMENT ON TABLE platform.audit_archive_head IS
    'The audit archive''s one head row (P16-TSK-010): the claim''s lock, FOR UPDATE SKIP LOCKED, and the last segment cut.';

INSERT INTO platform.audit_archive_head (head_id, last_segment_seq) VALUES (1, 0);

CREATE TABLE platform.audit_archive_segment (
    segment_seq              bigint      NOT NULL,
    state                    text        NOT NULL,
    row_count                integer     NOT NULL,
    first_audit_id           uuid        NOT NULL,
    last_audit_id            uuid        NOT NULL,
    first_occurred_at        timestamptz NOT NULL,
    last_occurred_at         timestamptz NOT NULL,
    holder                   uuid        NOT NULL,
    lease_until              timestamptz NOT NULL,
    reclaims                 integer     NOT NULL DEFAULT 0,
    claimed_at               timestamptz NOT NULL DEFAULT now(),
    content_sha256           text,
    manifest_sha256          text,
    previous_manifest_sha256 text,
    archived_at              timestamptz,

    CONSTRAINT audit_archive_segment_pk PRIMARY KEY (segment_seq),
    CONSTRAINT audit_archive_segment_seq_positive CHECK (segment_seq >= 1),
    CONSTRAINT audit_archive_segment_state_known CHECK (state IN ('CLAIMED', 'ARCHIVED')),
    CONSTRAINT audit_archive_segment_rows_bounded CHECK (row_count BETWEEN 1 AND 10000),
    CONSTRAINT audit_archive_segment_range_ordered CHECK (first_audit_id <= last_audit_id),
    CONSTRAINT audit_archive_segment_reclaims_counted CHECK (reclaims >= 0),
    CONSTRAINT audit_archive_segment_archived_is_sealed
        CHECK ((state = 'ARCHIVED') = (content_sha256 IS NOT NULL AND manifest_sha256 IS NOT NULL AND archived_at IS NOT NULL)),
    CONSTRAINT audit_archive_segment_digests_are_sha256
        CHECK ((content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$')
            AND (manifest_sha256 IS NULL OR manifest_sha256 ~ '^[0-9a-f]{64}$')
            AND (previous_manifest_sha256 IS NULL OR previous_manifest_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT audit_archive_segment_chained
        CHECK ((state = 'CLAIMED' AND previous_manifest_sha256 IS NULL)
            OR (state = 'ARCHIVED' AND (segment_seq = 1) = (previous_manifest_sha256 IS NULL)))
);

COMMENT ON TABLE platform.audit_archive_segment IS
    'One archived (or being archived) set of audit rows (P16-TSK-010): CLAIMED under a lease on the database clock, then ARCHIVED '
    'once, with the digests of the copy read back. The archival''s own record; the application role reads it and writes it only '
    'through claim_audit_archive_segment and complete_audit_archive_segment.';

COMMENT ON COLUMN platform.audit_archive_segment.first_occurred_at IS
    'The earliest occurred_at among the segment''s rows: descriptive, the period the manifest indexes. An instance''s clock wrote it, '
    'so it decides nothing.';

-- One CLAIMED segment at most, whatever reaches the table: the chain is cut in order.
CREATE UNIQUE INDEX audit_archive_segment_one_claimed
    ON platform.audit_archive_segment (state)
    WHERE state = 'CLAIMED';

CREATE TABLE platform.audit_archive_member (
    audit_id    uuid   NOT NULL,
    segment_seq bigint NOT NULL,

    -- Born once: an audit row belongs to one segment, ever.
    CONSTRAINT audit_archive_member_pk PRIMARY KEY (audit_id),
    CONSTRAINT audit_archive_member_segment_fk FOREIGN KEY (segment_seq) REFERENCES platform.audit_archive_segment (segment_seq)
);

-- No foreign key to platform.audit_record: it would take FOR KEY SHARE on every archived audit row, a write to that heap per row.
-- The claim checks instead that every queued id has its row, and refuses the segment if one does not.
CREATE INDEX audit_archive_member_by_segment ON platform.audit_archive_member (segment_seq, audit_id);

COMMENT ON TABLE platform.audit_archive_member IS
    'Which segment holds each audit row (P16-TSK-010). The primary key makes it one segment per row.';

CREATE TABLE platform.audit_archive_pending (
    audit_id    uuid        NOT NULL,
    enqueued_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT audit_archive_pending_pk PRIMARY KEY (audit_id)
);

CREATE INDEX audit_archive_pending_by_age ON platform.audit_archive_pending (enqueued_at);

COMMENT ON TABLE platform.audit_archive_pending IS
    'Audit rows no segment holds yet (P16-TSK-010): written by the trigger on platform.audit_record in the inserting transaction, '
    'moved into a segment by the claim. enqueued_at is the database''s clock.';

-- The review: the columns of platform.audit_record, their types and their order, as format 1 encodes them.
CREATE FUNCTION platform.audit_archive_reviewed()
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF (SELECT string_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod), ',' ORDER BY a.attnum)
          FROM pg_catalog.pg_attribute a
         WHERE a.attrelid = 'platform.audit_record'::regclass
           AND a.attnum > 0
           AND NOT a.attisdropped)
       IS DISTINCT FROM
       'audit_id:uuid,actor_id:text,actor_type:text,occurred_at:timestamp with time zone,operation:text,target_type:text,'
       'target_id:text,reason:text,outcome:text,correlation_id:text,change_summary:text'
    THEN
        RAISE EXCEPTION 'platform.audit_record has a column the audit archive''s format 1 was not reviewed against: nothing is archived (P16-TSK-010)'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
END;
$$;

-- Format 1's line: every column, in the table's order, as a JSON array; occurred_at in UTC to the microsecond.
CREATE FUNCTION platform.audit_archive_line(r platform.audit_record)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NOT isfinite(r.occurred_at) THEN
        RAISE EXCEPTION 'an audit record''s occurred_at is not a finite instant' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    RETURN json_build_array(
        r.audit_id::text,
        r.actor_id,
        r.actor_type,
        to_char(r.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        r.operation,
        r.target_type,
        r.target_id,
        r.reason,
        r.outcome,
        r.correlation_id,
        r.change_summary)::text;
END;
$$;

-- The read path's decoder: a line of format 1 back into a row of the table's type.
CREATE FUNCTION platform.audit_archive_record(line text)
    RETURNS platform.audit_record
    LANGUAGE plpgsql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    j json := line::json;
    r platform.audit_record;
BEGIN
    IF json_typeof(j) <> 'array' OR json_array_length(j) <> 11 THEN
        RAISE EXCEPTION 'not a line of the audit archive''s format 1' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    r.audit_id := (j ->> 0)::uuid;
    r.actor_id := j ->> 1;
    r.actor_type := j ->> 2;
    r.occurred_at := (j ->> 3)::timestamptz;
    r.operation := j ->> 4;
    r.target_type := j ->> 5;
    r.target_id := j ->> 6;
    r.reason := j ->> 7;
    r.outcome := j ->> 8;
    r.correlation_id := j ->> 9;
    r.change_summary := j ->> 10;
    RETURN r;
END;
$$;

-- The segment's file: its rows' lines in audit id order, each ended by a newline.
CREATE FUNCTION platform.audit_archive_content(p_segment_seq bigint)
    RETURNS text
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce(string_agg(platform.audit_archive_line(a) || E'\n', '' ORDER BY a.audit_id), '')
      FROM platform.audit_archive_member m
      JOIN platform.audit_record a ON a.audit_id = m.audit_id
     WHERE m.segment_seq = p_segment_seq
$$;

-- The segment's manifest, chained to its predecessor's digest.
CREATE FUNCTION platform.audit_archive_manifest(p_segment_seq bigint)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s platform.audit_archive_segment;
    v_previous text;
BEGIN
    SELECT * INTO s FROM platform.audit_archive_segment seg WHERE seg.segment_seq = p_segment_seq;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no audit archive segment %', p_segment_seq USING ERRCODE = 'no_data_found';
    END IF;
    IF p_segment_seq > 1 THEN
        SELECT seg.manifest_sha256 INTO v_previous
          FROM platform.audit_archive_segment seg
         WHERE seg.segment_seq = p_segment_seq - 1
           AND seg.state = 'ARCHIVED';
        IF v_previous IS NULL THEN
            RAISE EXCEPTION 'the audit archive segment before % is not archived' , p_segment_seq
                USING ERRCODE = 'object_not_in_prerequisite_state';
        END IF;
    END IF;
    RETURN json_build_object(
        'format', 'finapp.audit-archive/1',
        'segment', s.segment_seq,
        'rows', s.row_count,
        'first_audit_id', s.first_audit_id::text,
        'last_audit_id', s.last_audit_id::text,
        'first_occurred_at', to_char(s.first_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'last_occurred_at', to_char(s.last_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'content_sha256', encode(sha256(convert_to(platform.audit_archive_content(p_segment_seq), 'UTF8')), 'hex'),
        'previous_manifest_sha256', v_previous)::text;
END;
$$;

-- The claim: the CLAIMED segment taken over if its lease has lapsed, otherwise the next segment cut from the queue.
CREATE FUNCTION platform.claim_audit_archive_segment(lease_ms bigint, batch_size integer)
    RETURNS TABLE (claimed_seq bigint, claimed_holder uuid)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_seq bigint;
    v_lease timestamptz;
    v_holder uuid;
    v_ids uuid[];
    v_count integer;
    v_found integer;
    v_first_at timestamptz;
    v_last_at timestamptz;
    v_moved integer;
BEGIN
    IF lease_ms IS NULL OR lease_ms NOT BETWEEN 1000 AND 3600000 OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND 10000 THEN
        RAISE EXCEPTION 'an audit archive claim names a lease of 1 s to 1 h and a batch of 1 to 10000 (P16-TSK-010)'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    PERFORM platform.audit_archive_reviewed();

    PERFORM 1 FROM platform.audit_archive_head h WHERE h.head_id = 1 FOR UPDATE SKIP LOCKED;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT seg.segment_seq, seg.lease_until INTO v_seq, v_lease
      FROM platform.audit_archive_segment seg
     WHERE seg.state = 'CLAIMED'
       FOR UPDATE;
    IF FOUND THEN
        IF v_lease > now() THEN
            RETURN;
        END IF;
        v_holder := gen_random_uuid();
        UPDATE platform.audit_archive_segment seg
           SET holder = v_holder,
               lease_until = now() + lease_ms * INTERVAL '1 millisecond',
               reclaims = seg.reclaims + 1
         WHERE seg.segment_seq = v_seq;
        claimed_seq := v_seq;
        claimed_holder := v_holder;
        RETURN NEXT;
        RETURN;
    END IF;

    -- The ids are fixed once, in an array, so each statement below reads the same set whatever the queue gains meanwhile.
    SELECT array_agg(q.audit_id ORDER BY q.audit_id) INTO v_ids
      FROM (SELECT p.audit_id
              FROM platform.audit_archive_pending p
             ORDER BY p.enqueued_at, p.audit_id
             LIMIT batch_size
               FOR UPDATE) q;
    IF v_ids IS NULL THEN
        RETURN;
    END IF;
    v_count := cardinality(v_ids);

    SELECT count(*), min(a.occurred_at), max(a.occurred_at) INTO v_found, v_first_at, v_last_at
      FROM platform.audit_record a
     WHERE a.audit_id = ANY (v_ids);
    IF v_found <> v_count THEN
        RAISE EXCEPTION 'a queued audit id has no audit record: % queued, % found; nothing is archived over the gap (P16-TSK-010)',
            v_count, v_found
            USING ERRCODE = 'data_corrupted';
    END IF;

    SELECT h.last_segment_seq + 1 INTO v_seq FROM platform.audit_archive_head h WHERE h.head_id = 1;
    v_holder := gen_random_uuid();
    INSERT INTO platform.audit_archive_segment
        (segment_seq, state, row_count, first_audit_id, last_audit_id, first_occurred_at, last_occurred_at, holder, lease_until)
    VALUES
        (v_seq, 'CLAIMED', v_count, v_ids[1], v_ids[v_count], v_first_at, v_last_at, v_holder,
         now() + lease_ms * INTERVAL '1 millisecond');

    DELETE FROM platform.audit_archive_pending p WHERE p.audit_id = ANY (v_ids);
    GET DIAGNOSTICS v_moved = ROW_COUNT;
    IF v_moved <> v_count THEN
        RAISE EXCEPTION 'the audit archive queue moved % of % ids (P16-TSK-010)', v_moved, v_count USING ERRCODE = 'data_corrupted';
    END IF;
    INSERT INTO platform.audit_archive_member (audit_id, segment_seq) SELECT u.id, v_seq FROM unnest(v_ids) AS u(id);
    UPDATE platform.audit_archive_head h SET last_segment_seq = v_seq WHERE h.head_id = 1;

    claimed_seq := v_seq;
    claimed_holder := v_holder;
    RETURN NEXT;
END;
$$;

COMMENT ON FUNCTION platform.claim_audit_archive_segment(bigint, integer) IS
    'The audit archive''s claim (P16-TSK-010): one claimer at a time by the head row FOR UPDATE SKIP LOCKED; a lapsed CLAIMED segment '
    'taken over with a new holder, or the next segment cut from the queue; no row when there is nothing to do. SECURITY DEFINER as '
    'the migrator, search_path pinned; EXECUTE to the application role only.';

-- The mark: once, by the holder, with the digests of the copy read back, which must be the rows'.
CREATE FUNCTION platform.complete_audit_archive_segment(
        p_segment_seq bigint, p_holder uuid, p_content_sha256 text, p_manifest_sha256 text)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_state text;
    v_holder uuid;
    v_previous text;
BEGIN
    PERFORM platform.audit_archive_reviewed();
    SELECT seg.state, seg.holder INTO v_state, v_holder
      FROM platform.audit_archive_segment seg
     WHERE seg.segment_seq = p_segment_seq
       FOR UPDATE;
    IF NOT FOUND OR v_state <> 'CLAIMED' OR v_holder IS DISTINCT FROM p_holder THEN
        RAISE EXCEPTION 'audit archive segment % is not claimed by this holder', p_segment_seq USING ERRCODE = 'AAHLD';
    END IF;
    IF p_content_sha256 IS DISTINCT FROM encode(sha256(convert_to(platform.audit_archive_content(p_segment_seq), 'UTF8')), 'hex')
       OR p_manifest_sha256 IS DISTINCT FROM encode(sha256(convert_to(platform.audit_archive_manifest(p_segment_seq), 'UTF8')), 'hex')
    THEN
        RAISE EXCEPTION 'audit archive segment %: the copy''s digests are not the rows''', p_segment_seq USING ERRCODE = 'AADGS';
    END IF;
    IF p_segment_seq > 1 THEN
        SELECT seg.manifest_sha256 INTO v_previous FROM platform.audit_archive_segment seg WHERE seg.segment_seq = p_segment_seq - 1;
    END IF;
    UPDATE platform.audit_archive_segment seg
       SET state = 'ARCHIVED',
           content_sha256 = p_content_sha256,
           manifest_sha256 = p_manifest_sha256,
           previous_manifest_sha256 = v_previous,
           archived_at = now()
     WHERE seg.segment_seq = p_segment_seq;
END;
$$;

COMMENT ON FUNCTION platform.complete_audit_archive_segment(bigint, uuid, text, text) IS
    'The audit archive''s mark (P16-TSK-010): CLAIMED to ARCHIVED once, by the present holder (AAHLD otherwise), with digests the '
    'database recomputes from the rows (AADGS otherwise). SECURITY DEFINER as the migrator, search_path pinned; EXECUTE to the '
    'application role only.';

-- The queue's writer: every new audit row's id, in the transaction that writes the row.
CREATE FUNCTION platform.enqueue_audit_record_for_archive()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    INSERT INTO platform.audit_archive_pending (audit_id, enqueued_at) VALUES (NEW.audit_id, now());
    RETURN NULL;
END;
$$;

CREATE TRIGGER audit_record_queued_for_archive
    AFTER INSERT ON platform.audit_record
    FOR EACH ROW
    EXECUTE FUNCTION platform.enqueue_audit_record_for_archive();

-- The rows written before the trigger: queued while the trigger's lock holds every new insert back.
INSERT INTO platform.audit_archive_pending (audit_id, enqueued_at)
SELECT a.audit_id, now()
  FROM platform.audit_record a
    ON CONFLICT (audit_id) DO NOTHING;

REVOKE ALL ON FUNCTION platform.audit_archive_reviewed() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.enqueue_audit_record_for_archive() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.audit_archive_line(platform.audit_record) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.audit_archive_record(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.audit_archive_content(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.audit_archive_manifest(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.claim_audit_archive_segment(bigint, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.complete_audit_archive_segment(bigint, uuid, text, text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION platform.audit_archive_line(platform.audit_record) TO finapp_app;
GRANT EXECUTE ON FUNCTION platform.audit_archive_record(text) TO finapp_app;
GRANT EXECUTE ON FUNCTION platform.audit_archive_content(bigint) TO finapp_app;
GRANT EXECUTE ON FUNCTION platform.audit_archive_manifest(bigint) TO finapp_app;
GRANT EXECUTE ON FUNCTION platform.claim_audit_archive_segment(bigint, integer) TO finapp_app;
GRANT EXECUTE ON FUNCTION platform.complete_audit_archive_segment(bigint, uuid, text, text) TO finapp_app;

GRANT SELECT ON platform.audit_archive_segment TO finapp_app;
GRANT SELECT ON platform.audit_archive_member TO finapp_app;
GRANT SELECT ON platform.audit_archive_pending TO finapp_app;
