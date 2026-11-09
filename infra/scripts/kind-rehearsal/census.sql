-- The financial census of the kind rehearsal (P15-TSK-012): read from the database after every step, by the test-only server's own
-- superuser, because it spans schemas no application role may cross. Each statement prints one line: PASS|name|detail or FAIL|name|detail.
-- The harness keeps the output per step and any FAIL fails the step. Variables: expected_transfers, expected_topups, expected_checkouts,
-- expected_payouts, which the harness computes from what the smoke client wrote down that it had been told was done.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

-- Trial balance zero per currency (INV-ACC-01). An empty journal is a failure of the rehearsal: a sum over nothing proves nothing.
SELECT CASE WHEN count(*) > 0 AND bool_and(net = 0) THEN 'PASS' ELSE 'FAIL' END,
       'trial balance zero per currency',
       string_agg(currency || ': net ' || net || ' over ' || lines || ' lines', ', ')
  FROM (SELECT currency,
               sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net,
               count(*) AS lines
          FROM ledger.journal_line
         GROUP BY currency) balance;

-- Every entry's own debits and credits, the same invariant per entry: a compensating error across two entries would pass the sum above.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'every entry balances', count(*) || ' unbalanced entries'
  FROM (SELECT entry_id
          FROM ledger.journal_line
         GROUP BY entry_id, currency
        HAVING sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0) unbalanced;

-- One effect per idempotency key, transfers: the workload writes its key into the reference. One row per reference and one entry per row.
SELECT CASE WHEN rows = distinct_references AND rows = distinct_entries AND rows = :expected_transfers AND failed = 0
            THEN 'PASS' ELSE 'FAIL' END,
       'one transfer per key',
       rows || ' rows, ' || distinct_references || ' references, ' || distinct_entries || ' entries, ' || failed || ' not completed, client was told ' || :expected_transfers
  FROM (SELECT count(*) AS rows,
               count(DISTINCT reference) AS distinct_references,
               count(DISTINCT journal_entry_id) AS distinct_entries,
               count(*) FILTER (WHERE status <> 'COMPLETED') AS failed
          FROM transfers.transfer
         WHERE reference LIKE 'wl-%' OR reference LIKE 'smoke-%') t;

-- One effect per key, card payments: each pay-in the client was told succeeded is exactly one SUCCEEDED intent, top-ups and checkouts alike.
SELECT CASE WHEN count(*) = :expected_topups + :expected_checkouts THEN 'PASS' ELSE 'FAIL' END,
       'one captured intent per key',
       count(*) || ' SUCCEEDED intents, client was told ' || :expected_topups || ' top-ups and ' || :expected_checkouts || ' checkouts'
  FROM payments.payment_intent
 WHERE status = 'SUCCEEDED';

-- One effect per key, payouts: one row per key the client used, none refused.
SELECT CASE WHEN count(*) = :expected_payouts THEN 'PASS' ELSE 'FAIL' END,
       'one payout per key',
       count(*) || ' payouts, client was told ' || :expected_payouts
  FROM merchant.merchant_payout
 WHERE status IN ('PAID', 'INITIATED', 'DISPATCHED', 'SENT');

-- The outbox drained, and nothing stuck behind a dead letter.
SELECT CASE WHEN count(*) FILTER (WHERE published_at IS NULL AND discarded_at IS NULL) = 0 THEN 'PASS' ELSE 'FAIL' END,
       'the outbox drained',
       count(*) || ' events, ' || count(*) FILTER (WHERE published_at IS NULL AND discarded_at IS NULL) || ' pending, '
           || count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) || ' dead-lettered'
  FROM platform.outbox_event;

SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'no event dead-lettered', count(*) || ' dead letters'
  FROM platform.outbox_event
 WHERE dead_lettered_at IS NOT NULL;

-- Nothing a consumer could not apply: a parked record is a stopped partition until a person releases it.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'nothing parked', count(*) || ' parked records'
  FROM platform.inbox_parked_record;

-- No orphaned lease: a claim whose holder has gone and whose lease has run out, unreclaimed. A running claim's lease is short; one past its expiry
-- after the workload has stopped was held by a pod that died and has been left behind.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'no orphaned idempotency lease',
       count(*) || ' expired IN_PROGRESS claims (oldest ' || coalesce(extract(epoch FROM now() - min(lease_expires_at))::bigint, 0) || ' s)'
  FROM platform.idempotency_record
 WHERE state = 'IN_PROGRESS'
   AND lease_expires_at < now();

-- Every balance explainable from the journal: the posted projection against the lines that made it.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'balances explain the journal', count(*) || ' accounts differ'
  FROM (SELECT b.ledger_account_id
          FROM ledger.account_balance b
          LEFT JOIN (SELECT ledger_account_id,
                            sum(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) AS net
                       FROM ledger.journal_line
                      GROUP BY ledger_account_id) l USING (ledger_account_id)
         WHERE abs(coalesce(l.net, 0)) <> abs(b.balance_minor)) drift;

-- TLS, as the server sees it: no application connection without it (the server refuses one anyway; this reads the fact).
SELECT CASE WHEN count(*) FILTER (WHERE NOT s.ssl) = 0 AND count(*) > 0 THEN 'PASS' ELSE 'FAIL' END,
       'every fleet connection is TLS',
       count(*) || ' connections, ' || count(*) FILTER (WHERE s.ssl) || ' over TLS'
  FROM pg_stat_activity a
  JOIN pg_stat_ssl s USING (pid)
 WHERE a.application_name = 'finapp';
