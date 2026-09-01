-- Move the idempotency claim's lease onto the database clock.
--
-- THE DEFECT THIS CORRECTS
--   V002/V003 let a crashed process's claim be taken over once it was "stale", and staleness
--   was decided like this:
--
--       created_at (written by instance A's clock) < now - staleAfter (instance B's clock)
--
--   Two different clocks. With N instances that is not a lease, it is a race against clock
--   skew. If instance B's clock runs six minutes ahead of A's and the lease is five minutes,
--   B considers every claim A has just made to be abandoned, reclaims it, and runs the command
--   while A is still running it. That is two financial effects for one request — precisely
--   what INV-IDEM-01 exists to prevent.
--
--   It passed every test because the tests ran in one JVM with one clock. A single-instance
--   assumption is invisible until there are two instances, and by then it is in production.
--
-- THE CORRECTION
--   A lease is a coordination primitive, and coordination needs one clock that every instance
--   shares. The database is that clock. `lease_expires_at` is set by the server on claim and
--   compared against the server's `now()` on reclaim, so no instance's clock participates in
--   the decision at all.
--
-- WHY THIS DOES NOT CONTRADICT P0-TSK-013
--   That rule forbids application code reading ambient time for *business* decisions, and it
--   stands: created_at, completed_at and expires_at remain application-supplied from one
--   injected Clock, and the schema still declares no DEFAULT for them. This column is not a
--   business fact. It is a distributed lease boundary, and DOMAIN_MODEL.md §Time already
--   distinguishes system time from business dates — coordination time is system time, and the
--   only system clock a cluster agrees on is the database's.
--
-- INVARIANTS
--   INV-IDEM-01  One financial effect per key, now including across instances with skewed
--                clocks.
--   INV-LIFE-03  An unknown outcome stays unknown: a claim whose lease has not expired is
--                never assumed dead.

ALTER TABLE platform.idempotency_record
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN platform.idempotency_record.lease_expires_at IS
    'When this claim may be taken over by another instance. Set and compared using the '
    'database clock, never a client''s: a lease decided by two different clocks is a race '
    'against skew, not a lease. NULL once the claim is terminal - only a running claim is '
    'leased.';

-- Existing in-progress claims get a lease from the database clock, so no row is left in a
-- state where it can never be reclaimed. Terminal rows are deliberately untouched: V003's
-- trigger freezes them, and a terminal claim is not leased.
UPDATE platform.idempotency_record
SET lease_expires_at = now() + INTERVAL '5 minutes'
WHERE state = 'IN_PROGRESS'
  AND lease_expires_at IS NULL;

-- A running claim must be leased, or nothing can ever decide whether it is abandoned; a
-- terminal one must not be, or a reclaim query could match a finished command.
ALTER TABLE platform.idempotency_record
    ADD CONSTRAINT idempotency_record_running_claims_are_leased
        CHECK ((state = 'IN_PROGRESS') = (lease_expires_at IS NOT NULL));

-- The reclaim sweep looks for expired leases among running claims. This is the one query whose
-- shape is known, unlike the retention sweep V002 deliberately left unindexed, so the index it
-- needs is justified rather than guessed: partial, because terminal rows are the overwhelming
-- majority of the table and none of them is ever a candidate.
CREATE INDEX idempotency_record_expired_leases
    ON platform.idempotency_record (lease_expires_at)
    WHERE state = 'IN_PROGRESS';
