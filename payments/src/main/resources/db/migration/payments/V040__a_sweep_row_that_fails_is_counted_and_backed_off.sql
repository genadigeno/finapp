-- A sweep row that fails, or cannot be read, is counted and backed off (P16-TSK-017; THREAT_MODEL.md section 6 item 42 (i);
-- OPERATIONS_RUNBOOK.md section 11.39; INV-LIFE-03, INV-AUD-02).
--
-- WHY
--   The resolution sweeps take the oldest candidates first and contain each row, so a row that fails every tick stayed at
--   the head and took a slot of every batch, and a row whose id a raw writer corrupted stopped the listing itself. Each
--   listing now skips a row it cannot read, and every listing leaves a row that failed, read or not, until its retry is
--   due: one record per listing and row, counted once per backoff window however many instances failed it.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One table. Operational state, never financial truth: nothing reads it to state a balance or to conclude an operation;
--   it decides only when a listing reads a row again. A row named here is never concluded by being named (INV-LIFE-03).
--   No foreign key to the swept tables: a key would take a lock on those hot tables here and a KEY SHARE on their row at
--   every insert, against the resolvers' FOR UPDATE, and a row whose id is not a UUIDv7 is recorded all the same.
--   No column on any swept table: payment_attempt, refund, withdrawal and dispute_response are not touched.
--
-- LOCKS
--   CREATE TABLE locks only the new table. Expand-only: the previous release neither reads nor writes it.
--
-- WHAT IT DOES NOT TOUCH
--   No existing table, column, row, trigger or grant. It moves no money.

CREATE TABLE payments.sweep_row_failure (
    -- The listing the row failed in: SweepLeg's names exactly.
    leg              text        NOT NULL
        CONSTRAINT sweep_row_failure_leg_is_known
            CHECK (leg IN ('CARD_ATTEMPT', 'CARD_STRANDED', 'CARD_REFUND', 'RETURN', 'WITHDRAWAL', 'DISPUTE_ANSWER',
                           'PAY_IN')),
    -- The swept row's id as its column holds it, whatever its version.
    row_id           uuid        NOT NULL,
    -- Consecutive failures: the backoff's exponent.
    failures         integer     NOT NULL
        CONSTRAINT sweep_row_failure_failures_are_counted CHECK (failures >= 1),
    first_failed_at  timestamptz NOT NULL,
    last_failed_at   timestamptz NOT NULL,
    -- When a listing may read the row again, on the database's clock.
    retry_at         timestamptz NOT NULL,
    -- The failure's class name and nothing else (INV-AUD-02): no message, host, reference or value can be stored.
    last_failure     text        NOT NULL
        CONSTRAINT sweep_row_failure_last_failure_is_a_class_name
            CHECK (last_failure ~ '^[A-Za-z][A-Za-z0-9_$]{0,127}$'),
    CONSTRAINT sweep_row_failure_is_one_per_leg_and_row PRIMARY KEY (leg, row_id),
    CONSTRAINT sweep_row_failure_runs_forward CHECK (last_failed_at >= first_failed_at AND retry_at > last_failed_at)
);

COMMENT ON TABLE payments.sweep_row_failure IS
    'A sweep row that failed or could not be read, and when its listing reads it again (P16-TSK-017). Operational state, never financial truth.';

GRANT SELECT, INSERT ON payments.sweep_row_failure TO finapp_app;
GRANT UPDATE (failures, first_failed_at, last_failed_at, retry_at, last_failure) ON payments.sweep_row_failure TO finapp_app;
