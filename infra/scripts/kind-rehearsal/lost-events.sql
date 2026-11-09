-- A generic lost-event check of the kind rehearsal: every published event of a type the handler catalogue names has its inbox record for the consumer the catalogue
-- names. The offset a consumer commits says where it got to; this says what it applied. Variables: handlers, a VALUES list of (consumer, event type); added_event and
-- added_consumer, the handler N adds, whose window has assertions of its own and which N - 1 legitimately leaves unseen until the replay.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END,
       'no event lost to a catalogued handler',
       count(*) || ' published events with no inbox record: ' || coalesce(string_agg(DISTINCT h.consumer || ' <- ' || e.event_type, ', '), 'none')
  FROM platform.outbox_event e
  JOIN (VALUES :handlers) AS h (consumer, event_type) ON h.event_type = e.event_type
  LEFT JOIN platform.inbox_message i ON i.consumer = h.consumer AND i.dedupe_key = e.event_id::text
 WHERE e.published_at IS NOT NULL
   AND i.dedupe_key IS NULL
   AND NOT (e.event_type = :'added_event' AND h.consumer = :'added_consumer');
