-- The waits on the contended rows, sampled every 250 ms during a load whose profile asks for it ("contention": true; P16-TSK-008,
-- PHASE_16_PLAN.md sections 6.3-6.4, OPERATIONS_RUNBOOK.md section 11.48). One psql session of the test-only server's superuser in the
-- finapp database runs this under \watch for the load's length (kind-rehearsal/load.sh, load_contention_start, passes :count); every
-- run of the statement is its own transaction, so the sampler holds no snapshot open across samples and holds back no vacuum.
--
-- Each run prints one 'tick' line (the sample's time and how many backends were waiting on a lock), so a sample with no wait still
-- shows that the sampler was alive, and one 'wait' line per client backend of finapp waiting on a heavyweight lock:
--
--   wait|<time>|<pid>|<its transaction's start>|<wait event>|<target>|<how many block it>|<its statement, parameterised>
--
-- The target is the row it waits for, resolved where the lock says which: a waiter on a row holds or awaits that row's tuple lock
-- (the first waiter holds it while it waits on the holder's transaction; the others queue on it), and the tuple's position is read
-- back to the row while the holder has not committed, which is while the wait lasts. A balance projection row or an account row names
-- its account's purpose, currency and id (an account id, never a customer's or an amount); an advisory lock names its namespace and
-- key; any other row its table; a table lock its table (an index build's write stop: E39). A row the holder committed past between
-- the wait and the read is "moved"; a wait the lock table does not place is the wait event with "unresolved", and its statement says
-- which path it is. kind-rehearsal/load/contention.awk turns the lines into each row's waits per stage.
--
-- It reads the server's views and the two ledger tables by position only: no row of any other table, no amount, no credential.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

WITH sample AS (
    SELECT round(extract(epoch FROM clock_timestamp())::numeric, 3) AS at
), waiting AS (
    SELECT a.pid, a.xact_start, a.wait_event, a.query
      FROM pg_stat_activity a
     WHERE a.wait_event_type = 'Lock'
       AND a.datname = 'finapp'
       AND a.backend_type = 'client backend'
)
SELECT 'tick', s.at, (SELECT count(*) FROM waiting), NULL, NULL, NULL, NULL, NULL
  FROM sample s
UNION ALL
SELECT 'wait', s.at, w.pid, round(extract(epoch FROM w.xact_start)::numeric, 3), w.wait_event,
       coalesce(t.target, w.wait_event || ':unresolved'), cardinality(pg_blocking_pids(w.pid)),
       left(regexp_replace(w.query, '\s+', ' ', 'g'), 200)
  FROM sample s
 CROSS JOIN waiting w
  LEFT JOIN LATERAL (
        SELECT CASE
                 WHEN l.locktype = 'advisory' THEN 'advisory:' || l.classid || ':' || l.objid
                 WHEN l.locktype = 'tuple' AND l.relation = 'ledger.account_balance'::regclass THEN
                      coalesce((SELECT 'balance:' || la.purpose || ':' || la.currency || ':' || la.id
                                  FROM ledger.account_balance b
                                  JOIN ledger.ledger_account la ON la.id = b.ledger_account_id
                                 WHERE b.ctid = format('(%s,%s)', l.page, l.tuple)::tid), 'balance:moved')
                 WHEN l.locktype = 'tuple' AND l.relation = 'ledger.ledger_account'::regclass THEN
                      coalesce((SELECT 'account:' || la.purpose || ':' || la.currency || ':' || la.id
                                  FROM ledger.ledger_account la
                                 WHERE la.ctid = format('(%s,%s)', l.page, l.tuple)::tid), 'account:moved')
                 WHEN l.locktype = 'tuple' THEN 'row:' || l.relation::regclass::text
                 ELSE 'table:' || l.relation::regclass::text
               END AS target
          FROM pg_locks l
         WHERE l.pid = w.pid
           AND ((l.locktype = 'tuple') OR (l.locktype IN ('advisory', 'relation') AND NOT l.granted))
         ORDER BY l.granted, l.locktype = 'tuple' DESC
         LIMIT 1) t ON true
\watch interval=0.25 count=:count
