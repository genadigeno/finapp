-- The financial census of the kind rehearsal (P15-TSK-012): read from the database after every step, by the test-only server's own
-- superuser, because it spans schemas no application role may cross. Each statement prints one line: PASS|name|detail or FAIL|name|detail.
-- The harness keeps the output per step and any FAIL fails the step. Variables: expected_transfers, expected_topups, expected_checkouts,
-- expected_payouts, expected_wallets, which the harness computes from what the smoke client wrote down that it had been told was done.
--
-- SAMPLED DURING LOAD (P16-TSK-006, PHASE_16_PLAN.md section 5.3). A clause whose last comment line is the marker below reads
-- committed facts only: it needs no count from the client and no quiet platform, so it holds at every committed instant. The load
-- scope (kind-rehearsal/load.sh) extracts exactly those clauses and runs them every 30 s inside one REPEATABLE READ READ ONLY
-- transaction, a consistent cut, while the load runs. A clause marked here that could fail mid-flight would fail a healthy run; a
-- committed-fact clause left unmarked would go unread during load. Every clause, marked or not, still runs after every step.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

-- Trial balance zero per currency (INV-ACC-01). An empty journal is a failure of the rehearsal: a sum over nothing proves nothing.
-- [sampled during load]
SELECT CASE WHEN count(*) > 0 AND bool_and(net = 0) THEN 'PASS' ELSE 'FAIL' END,
       'trial balance zero per currency',
       string_agg(currency || ': net ' || net || ' over ' || lines || ' lines', ', ')
  FROM (SELECT currency,
               sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net,
               count(*) AS lines
          FROM ledger.journal_line
         GROUP BY currency) balance;

-- Every entry's own debits and credits, the same invariant per entry: a compensating error across two entries would pass the sum above.
-- [sampled during load]
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'every entry balances', count(*) || ' unbalanced entries'
  FROM (SELECT entry_id
          FROM ledger.journal_line
         GROUP BY entry_id, currency
        HAVING sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0) unbalanced;

-- One effect per key, at the ledger (INV-IDEM-01): a posting's scope is its command's key, and no scope is posted twice. The column is not
-- UNIQUE yet (P16-TSK-011 lands that, after a census of exactly this), so this is the check that reads it, at every step and under load.
-- [sampled during load]
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'one journal entry per posting scope',
       count(*) || ' scopes posted more than once'
  FROM (SELECT idempotency_scope
          FROM ledger.journal_entry
         GROUP BY idempotency_scope
        HAVING count(*) > 1) twice;

-- One effect per key, transfers, at any instant: the rehearsal writes a fresh reference with each key (smoke-, wl-), and a retry under the
-- key sends the same one, so a reference on two transfers is one key's second effect. Only the rehearsal's own: a customer's free-text
-- reference may repeat. The count against the client's is "one transfer per key" below, after the load.
-- [sampled during load]
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'one transfer per rehearsal reference',
       count(*) || ' references carried by more than one transfer'
  FROM (SELECT reference
          FROM transfers.transfer
         WHERE reference LIKE 'wl-%' OR reference LIKE 'smoke-%'
         GROUP BY reference
        HAVING count(*) > 1) twice;

-- One effect per key, card payments, at any instant: a capture's transition, its posting (payment-capture:<attempt>), its intent's
-- SUCCEEDED and its expectation commit together (PaymentOutcomes.applyCapture, ADR-0048), so every captured card attempt has exactly one
-- capture posting, and every capture posting names a captured attempt, in any committed snapshot.
-- [sampled during load]
SELECT CASE WHEN missing + orphaned = 0 THEN 'PASS' ELSE 'FAIL' END, 'one capture posting per captured card attempt',
       missing || ' captured card attempts without exactly one capture posting, ' || orphaned || ' capture postings naming no captured attempt'
  FROM (SELECT count(*) FILTER (WHERE a.card AND coalesce(p.entries, 0) <> 1) AS missing,
               count(*) FILTER (WHERE a.scope IS NULL) AS orphaned
          FROM (SELECT 'ledger.post:payment-capture:' || id AS scope, rail = 'card' AS card
                  FROM payments.payment_attempt
                 WHERE status = 'CAPTURED') a
          FULL JOIN (SELECT idempotency_scope AS scope, count(*) AS entries
                       FROM ledger.journal_entry
                      WHERE idempotency_scope LIKE 'ledger.post:payment-capture:%'
                      GROUP BY idempotency_scope) p
            ON p.scope = a.scope) captures;

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
 WHERE status = 'COMPLETED';

-- The postings behind them, which the intents and payouts above cannot show: a capture posted twice, or not at all, leaves its intent SUCCEEDED either way.
SELECT CASE WHEN count(*) = :expected_topups + :expected_checkouts THEN 'PASS' ELSE 'FAIL' END,
       'one posting per card capture',
       count(*) || ' entries, client was told ' || (:expected_topups + :expected_checkouts)
  FROM ledger.journal_entry
 WHERE idempotency_scope LIKE 'ledger.post:payment-capture:%';

SELECT CASE WHEN count(*) = :expected_payouts THEN 'PASS' ELSE 'FAIL' END,
       'one posting per payout',
       count(*) || ' entries, client was told ' || :expected_payouts
  FROM ledger.journal_entry
 WHERE idempotency_scope LIKE 'ledger.post:merchant-payout:%';

-- The amounts: every top-up the client was told succeeded credited one wallet, and transfers only move money between the same wallets. A wrong amount passes every count above.
SELECT CASE WHEN coalesce(sum(b.posted_minor), 0) = :expected_wallets THEN 'PASS' ELSE 'FAIL' END,
       'the wallets hold what the client put in',
       coalesce(sum(b.posted_minor), 0) || ' minor units in ' || count(*) || ' wallets, client put in ' || :expected_wallets
  FROM ledger.account_balance b
  JOIN ledger.ledger_account a ON a.id = b.ledger_account_id
 WHERE a.purpose = 'CUSTOMER_WALLET';

-- Nothing left in flight: with the workload stopped, an intent or a payout still between states is one nobody will finish.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'no payment intent left non-terminal',
       count(*) || ' intents awaiting confirmation or processing'
  FROM payments.payment_intent
 WHERE status IN ('REQUIRES_CONFIRMATION', 'PROCESSING');

SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'no payout left unresolved',
       count(*) || ' payouts neither completed nor failed'
  FROM merchant.merchant_payout
 WHERE status NOT IN ('COMPLETED', 'FAILED');

-- The outbox drained, and nothing stuck behind a dead letter.
SELECT CASE WHEN count(*) FILTER (WHERE published_at IS NULL AND discarded_at IS NULL) = 0 THEN 'PASS' ELSE 'FAIL' END,
       'the outbox drained',
       count(*) || ' events, ' || count(*) FILTER (WHERE published_at IS NULL AND discarded_at IS NULL) || ' pending, '
           || count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) || ' dead-lettered'
  FROM platform.outbox_event;

-- [sampled during load]
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'no event dead-lettered', count(*) || ' dead letters'
  FROM platform.outbox_event
 WHERE dead_lettered_at IS NOT NULL;

-- Nothing a consumer could not apply: a parked record is a stopped partition until a person releases it. A record two persons
-- discarded through the door (an approved discard of it, whose approver is not its proposer) is decided, not stopped, and its row
-- stays as evidence, never swept: E68's poison records end so (P16-TSK-013). Every other row counts, a handled one included.
-- [sampled during load]
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'nothing parked', count(*) || ' parked records'
  FROM platform.inbox_parked_record p
 WHERE NOT (p.state = 'DISCARDED'
            AND EXISTS (SELECT 1 FROM platform.dead_letter_discard d
                         WHERE d.subject_kind = 'INBOX_RECORD' AND d.subject_id = p.park_id AND d.status = 'APPROVED'));

-- No orphaned lease: a claim whose holder has gone and whose lease has run out, unreclaimed. A running claim's lease is short; one past its expiry
-- after the workload has stopped was held by a pod that died and has been left behind.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'no orphaned idempotency lease',
       count(*) || ' expired IN_PROGRESS claims (oldest ' || coalesce(extract(epoch FROM now() - min(lease_expires_at))::bigint, 0) || ' s)'
  FROM platform.idempotency_record
 WHERE state = 'IN_PROGRESS'
   AND lease_expires_at < now();

-- Every balance explainable from the journal: the posted projection against the lines that made it, on the account's own normal side, so a sign flipped is a difference and a line with no balance row is one too.
-- The projection moves in the posting's own transaction (ADR-0041), so this holds in any committed snapshot as well.
-- [sampled during load]
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, 'balances explain the journal', count(*) || ' accounts differ'
  FROM (SELECT ledger_account_id
          FROM ledger.account_balance b
          FULL JOIN (SELECT l.ledger_account_id,
                            sum(CASE WHEN l.direction = a.normal_balance THEN l.amount_minor ELSE -l.amount_minor END) AS net
                       FROM ledger.journal_line l
                       JOIN ledger.ledger_account a ON a.id = l.ledger_account_id
                      GROUP BY l.ledger_account_id) j USING (ledger_account_id)
         WHERE coalesce(j.net, 0) <> coalesce(b.posted_minor, 0)) drift;

-- TLS, as the server sees it: no application connection without it (the server refuses one anyway; this reads the fact).
SELECT CASE WHEN count(*) FILTER (WHERE NOT s.ssl) = 0 AND count(*) > 0 THEN 'PASS' ELSE 'FAIL' END,
       'every fleet connection is TLS',
       count(*) || ' connections, ' || count(*) FILTER (WHERE s.ssl) || ' over TLS'
  FROM pg_stat_activity a
  JOIN pg_stat_ssl s USING (pid)
 WHERE a.application_name = 'finapp';
