-- Scheduling state for the outbox relay (P0-TSK-020, ADR-0005, ADR-0014).
--
-- V005 gave the outbox everything needed to RECORD an event and nothing needed to RETRY one.
-- `attempts` counts, but nothing says when a failed row becomes eligible again and nothing says
-- when to stop trying. Without both, a broker outage turns the relay into a hot loop against a
-- dead endpoint, and a permanently unpublishable row is retried forever while every later event
-- for its aggregate waits behind it, unreported.
--
-- WHY THESE TIMESTAMPS TAKE THE SERVER'S CLOCK
--   `next_attempt_at` and `dead_lettered_at` are coordination boundaries, not business facts.
--   Every instance evaluates `next_attempt_at <= now()`, so the value must be written by the
--   same clock that evaluates it. This is the V004 lesson applied before it can bite again: a
--   lease compared across two instances' clocks is a race against skew, not a boundary.
--
--   `occurred_at` remains what it was - an application-supplied business timestamp from one
--   injected Clock (P0-TSK-013). The distinction is deliberate and is why one of these columns
--   carries a DEFAULT and the other does not.

ALTER TABLE platform.outbox_event
    -- When this row may next be attempted. A newly written row is eligible immediately, which
    -- is what the DEFAULT says; the relay pushes it forward on each failure.
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Set when the row has failed often enough that retrying is no longer useful. It is NOT a
    -- deletion and NOT a skip: see the note on ordering below.
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,

    -- Diagnosis only. Bounded, and never the payload: a publisher adapter must not put event
    -- content into its exception message, because this column is read by operators and appears
    -- in operational tooling (INV-AUD-02).
    ADD COLUMN last_error TEXT;

ALTER TABLE platform.outbox_event
    -- A row cannot be both published and abandoned. If it could, "how many events did we fail
    -- to deliver" would have two contradictory answers.
    ADD CONSTRAINT outbox_event_not_both_published_and_dead
        CHECK (published_at IS NULL OR dead_lettered_at IS NULL),

    -- Abandoning a row that was never attempted would mean the relay gave up without trying.
    ADD CONSTRAINT outbox_event_dead_implies_attempted
        CHECK (dead_lettered_at IS NULL OR attempts >= 1),

    ADD CONSTRAINT outbox_event_last_error_bounded
        CHECK (last_error IS NULL OR length(last_error) BETWEEN 1 AND 1000);

COMMENT ON COLUMN platform.outbox_event.next_attempt_at IS
    'When this row becomes eligible for another publication attempt. Set by the server clock '
    'because every instance compares it against the server clock (ADR-0014).';

COMMENT ON COLUMN platform.outbox_event.dead_lettered_at IS
    'Set when the relay stopped retrying. The row is NOT skipped: it blocks its aggregate '
    'until an operator resolves it, because silently continuing past it would leave consumers '
    'with a gap they cannot detect.';

-- ORDERING, AND WHY A DEAD ROW BLOCKS RATHER THAN BEING SKIPPED
--
-- ADR-0005 preserves ordering per aggregate. That guarantee is only as strong as its weakest
-- path, and the weakest path is failure: if event 2 may be published while event 1 is still
-- unpublished, ordering holds on the happy path and nowhere else.
--
-- So the relay stops at the first row of an aggregate it cannot publish - whether that row is
-- backing off or dead-lettered - and the aggregate's stream stalls. A stalled aggregate is
-- loud, bounded to one aggregate, and visible as backlog age. The alternative, skipping the
-- poisoned row and carrying on, is quiet: consumers receive events 1 and 3 with no way to know
-- 2 existed, and in a financial platform an undetectable gap in an event stream is strictly
-- worse than a stall somebody has to look at.
--
-- The index therefore matches the query the relay actually runs: pending rows, per aggregate,
-- in event order. V005's index was on (event_id) alone, which cannot serve the per-aggregate
-- scan - it was written before the relay's access pattern existed and is superseded here
-- rather than kept alongside, because an unused index is write cost with no reader.
DROP INDEX platform.outbox_event_pending;

CREATE INDEX outbox_event_pending
    ON platform.outbox_event (aggregate_id, event_id)
    WHERE published_at IS NULL;
