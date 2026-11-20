-- TEST-ONLY (P17-TST-001). Scenario 6's reading, after the console's spec: duplicate approvals converging on one, with one audit
-- record. One event operator proposed discarding the parked record; the other approved it twice - a double click under one key, and
-- again from a second tab - and the journal holds the answers: here, one decision, one effect, one record of it. A parked record, not
-- an abandoned event: a discarded inbox record is decided, which the census admits, while an outbox dead letter stays one.
-- Variables: since; park (the parked record's id); eventsa; eventsb.
SELECT CASE WHEN count(*) = 1 AND count(*) FILTER (WHERE status = 'APPROVED' AND proposed_by IN (:'eventsa', :'eventsb')
                 AND decided_by IN (:'eventsa', :'eventsb') AND proposed_by <> decided_by) = 1 THEN 'PASS' ELSE 'FAIL' END,
       'one discard of the record, approved, four eyes between the two',
       count(*) || ' discards of the record, ' || count(*) FILTER (WHERE status = 'APPROVED' AND proposed_by IN (:'eventsa', :'eventsb')
           AND decided_by IN (:'eventsa', :'eventsb') AND proposed_by <> decided_by) || ' approved so'
  FROM platform.dead_letter_discard
 WHERE subject_kind = 'INBOX_RECORD' AND subject_id = :'park'::uuid;
SELECT CASE WHEN count(*) = 1 THEN 'PASS' ELSE 'FAIL' END, 'the record discarded',
       count(*) || ' DISCARDED parked records of that id'
  FROM platform.inbox_parked_record
 WHERE park_id = :'park'::uuid AND state = 'DISCARDED';
SELECT CASE WHEN count(*) = 1 AND count(*) FILTER (WHERE outcome = 'SUCCEEDED') = 1 THEN 'PASS' ELSE 'FAIL' END,
       'one audit record of the discard, SUCCEEDED',
       count(*) || ' inbox.ParkedRecordDiscarded records of the record since the start, ' || count(*) FILTER (WHERE outcome = 'SUCCEEDED')
           || ' SUCCEEDED'
  FROM platform.audit_record
 WHERE operation = 'inbox.ParkedRecordDiscarded' AND target_id = :'park' AND occurred_at >= :'since'::timestamptz;
