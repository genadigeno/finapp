-- A rematch residual that faults is counted and left out of the rematch worklist until its retry (P16-TSK-017;
-- OPERATIONS_RUNBOOK.md section 11.39 (c); INV-REC-05, INV-AUD-02).
--
-- WHY
--   The rematch leg tries each residual against later evidence under a savepoint of its own, and a fault rolled that item
--   back with only a WARN line to show for it, so a residual faulting every tick re-headed the worklist for ever. Its fault
--   is now recorded in the batch's own transaction, under the source's try-lock and the item's row lock, and the worklist
--   leaves it out until its retry is due: a durable exclusion, bounded in time, so a transient fault is still retried.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One table. Operational state, never financial truth: it never touches the item, its decisions, parks, suspense or
--   breaks, so an excluded residual stays visible as a break, aged and alerted as any (INV-REC-05). Only the rematch
--   worklist reads it: the grace, run and reprocess legs and the run's pending-rematch count do not. No foreign key to
--   external_item: no lock on that table here.
--
-- LOCKS
--   CREATE TABLE locks only the new table. Expand-only: the previous release neither reads nor writes it.
--
-- WHAT IT DOES NOT TOUCH
--   No existing table, column, row, trigger or grant. It moves no money.

CREATE TABLE reconciliation.rematch_fault (
    external_item_id uuid        NOT NULL
        CONSTRAINT rematch_fault_is_one_per_item PRIMARY KEY,
    -- Consecutive faults: the backoff's exponent.
    faults           integer     NOT NULL
        CONSTRAINT rematch_fault_faults_are_counted CHECK (faults >= 1),
    first_faulted_at timestamptz NOT NULL,
    last_faulted_at  timestamptz NOT NULL,
    -- When the rematch worklist reads the residual again, on the database's clock.
    retry_at         timestamptz NOT NULL,
    -- The fault's class name and nothing else (INV-AUD-02).
    last_fault       text        NOT NULL
        CONSTRAINT rematch_fault_last_fault_is_a_class_name CHECK (last_fault ~ '^[A-Za-z][A-Za-z0-9_$]{0,127}$'),
    CONSTRAINT rematch_fault_runs_forward CHECK (last_faulted_at >= first_faulted_at AND retry_at > last_faulted_at)
);

COMMENT ON TABLE reconciliation.rematch_fault IS
    'A rematch residual that faulted, and when the rematch worklist reads it again (P16-TSK-017). Operational state, never financial truth; the residual stays a break.';

GRANT SELECT, INSERT ON reconciliation.rematch_fault TO finapp_app;
GRANT UPDATE (faults, first_faulted_at, last_faulted_at, retry_at, last_fault) ON reconciliation.rematch_fault TO finapp_app;
