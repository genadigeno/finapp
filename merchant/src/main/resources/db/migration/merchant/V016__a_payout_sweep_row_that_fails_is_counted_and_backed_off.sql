-- A payout sweep row that fails, or cannot be read, is counted and backed off (P16-TSK-017; THREAT_MODEL.md section 6
-- item 42 (i); OPERATIONS_RUNBOOK.md section 11.39; INV-LIFE-03, INV-AUD-02). The payments V040 record, in this schema.
--
-- WHY
--   The payout sweep takes the oldest payouts first and contains each row, so a payout that fails every tick stayed at
--   the head and took a slot of every batch, and one whose id a raw writer corrupted stopped the listing itself. The
--   listing now skips a row it cannot read, and leaves a row that failed, read or not, until its retry is due: one record
--   per row, counted once per backoff window however many instances failed it.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One table. Operational state, never financial truth: nothing reads it to state a payable or to conclude a payout;
--   it decides only when the listing reads a row again. No foreign key to merchant_payout (no lock on that table here,
--   no KEY SHARE against the resolvers' FOR UPDATE at every insert) and no column on it.
--
-- LOCKS
--   CREATE TABLE locks only the new table. Expand-only: the previous release neither reads nor writes it.
--
-- WHAT IT DOES NOT TOUCH
--   No existing table, column, row, trigger or grant. It moves no money.

CREATE TABLE merchant.sweep_row_failure (
    leg              text        NOT NULL
        CONSTRAINT sweep_row_failure_leg_is_known CHECK (leg IN ('PAYOUT')),
    row_id           uuid        NOT NULL,
    failures         integer     NOT NULL
        CONSTRAINT sweep_row_failure_failures_are_counted CHECK (failures >= 1),
    first_failed_at  timestamptz NOT NULL,
    last_failed_at   timestamptz NOT NULL,
    retry_at         timestamptz NOT NULL,
    -- The failure's class name and nothing else (INV-AUD-02).
    last_failure     text        NOT NULL
        CONSTRAINT sweep_row_failure_last_failure_is_a_class_name
            CHECK (last_failure ~ '^[A-Za-z][A-Za-z0-9_$]{0,127}$'),
    CONSTRAINT sweep_row_failure_is_one_per_leg_and_row PRIMARY KEY (leg, row_id),
    CONSTRAINT sweep_row_failure_runs_forward CHECK (last_failed_at >= first_failed_at AND retry_at > last_failed_at)
);

COMMENT ON TABLE merchant.sweep_row_failure IS
    'A payout sweep row that failed or could not be read, and when its listing reads it again (P16-TSK-017). Operational state, never financial truth.';

GRANT SELECT, INSERT ON merchant.sweep_row_failure TO finapp_app;
GRANT UPDATE (failures, first_failed_at, last_failed_at, retry_at, last_failure) ON merchant.sweep_row_failure TO finapp_app;
