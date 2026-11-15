-- The report run's opening bounded by a checkpoint for each locked month (P16-REM-003; THREAT_MODEL.md section 6 item 64; ADR-0142's
-- follow-up). Every report definition's opening - the trial balance's and its ledger windows, the balance sheet's, the GL activity's,
-- the purpose reports' - summed every journal line dated before a day, from inception: one statement whose cost grew with the
-- journal's life (CAPACITY_MODEL.md section 3.6). A read now takes the latest checkpoint whose month still stands under the lock it
-- was folded under, and folds only the lines dated after that month.
--
-- WHAT A CHECKPOINT IS
--   For one lock of one month: every journal line dated on or before the month's end, summed in the three shapes the opening reads
--   take - GL (purpose, owner kind, currency, scale, direction), LEDGER (currency, scale, direction, no account joined: the ledger's
--   own side, folded by its own statement, so the two sides of a trial balance never come from one sum) and COUNTERPARTY (the
--   counterparty accounts' lines per counterparty code). The journal's own classification, never a GL account: a mapping version
--   is applied on the read, so no version change can stale a checkpoint. latest_created is the latest creation instant of the entries
--   it folds, so a PROVISIONAL run cut at its claim takes one only when the cut is at or after it - the journal's created_at is an
--   instance's clock and the lock's locked_at the database's, so the lock's instant alone could not say which entries a cut keeps.
--
-- WHY IT HOLDS
--   ADR-0114's posting cutoff: from a month's lock commit no entry dated on or before its end commits, until the cutoff moves back -
--   which only the month's unlock or reopen does, each clearing the row's locked_at, and a relock stamps a new one (V003's period
--   machine; periods lock in order, so only the latest locked month can move back). A checkpoint is valid while its month's row is
--   not OPEN and its locked_at is the one the checkpoint was folded under: one equality, the same lock identity
--   period_unlock_end already keys on. A move back voids it without touching it; the read folds from the checkpoint before, or whole.
--
-- WHO WRITES IT, AND WHEN
--   Not the lock's own transaction: that one holds the cutoff's key exclusively until it commits, and a month's fold inside it would
--   hold every posting in the platform behind it. accounting's close schedule writes it after the lock commits (OpeningCheckpoints,
--   its openings leg), one month a tick, oldest first, under a transaction-scoped try-lock on namespace 14 so ten instances fold it
--   once. The insert reads the period row again and writes nothing unless the lock it folded under still stands; a duplicate of the
--   same lock is the unique's ON CONFLICT DO NOTHING.
--
-- NEVER WRITTEN OVER: the application role holds SELECT and INSERT only - no UPDATE, no DELETE; a checkpoint voided by a move back
-- stays, as evidence of what was read under that lock. The reporting role reads both tables (report generation's closed register).
-- Kept, never swept (DATA_RETENTION.md). No foreign key to the ledger (ADR-0029). No money moves: the figures a run reports are those
-- of the whole fold, which the database cases hold equal across several locked months, a move back and a FINAL run's regeneration.

CREATE TABLE accounting.opening_checkpoint (
    id               uuid        PRIMARY KEY,
    period_id        uuid        NOT NULL REFERENCES accounting.accounting_period (id),
    period_locked_at timestamptz NOT NULL,
    latest_created   timestamptz,
    written_at       timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT opening_checkpoint_one_per_lock UNIQUE (period_id, period_locked_at)
);

COMMENT ON TABLE accounting.opening_checkpoint IS
    'One locked month''s opening checkpoint (P16-REM-003): every journal line dated on or before the month''s end, folded under the lock period_locked_at names. Valid while the period row is not OPEN and its locked_at equals period_locked_at; latest_created is the latest entry creation instant it folds, NULL when none.';

CREATE TABLE accounting.opening_checkpoint_group (
    checkpoint_id uuid     NOT NULL REFERENCES accounting.opening_checkpoint (id),
    shape         text     NOT NULL,
    purpose       text,
    owner_kind    text,
    counterparty  text,
    currency      text     NOT NULL,
    scale         smallint NOT NULL,
    direction     text     NOT NULL,
    total         numeric  NOT NULL,
    lines         bigint   NOT NULL,
    CONSTRAINT opening_checkpoint_group_shape_is_known CHECK (shape IN ('GL', 'LEDGER', 'COUNTERPARTY')),
    CONSTRAINT opening_checkpoint_group_shape_holds_its_columns CHECK (
        (shape = 'LEDGER' AND purpose IS NULL AND owner_kind IS NULL AND counterparty IS NULL)
        OR (shape = 'GL' AND purpose IS NOT NULL AND owner_kind IS NOT NULL AND counterparty IS NULL)
        OR (shape = 'COUNTERPARTY' AND purpose IS NOT NULL AND owner_kind = 'COUNTERPARTY')),
    CONSTRAINT opening_checkpoint_group_currency_is_a_code CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT opening_checkpoint_group_scale_is_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT opening_checkpoint_group_direction_is_known CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT opening_checkpoint_group_total_is_whole_and_positive CHECK (total > 0 AND total = trunc(total)),
    CONSTRAINT opening_checkpoint_group_lines_are_counted CHECK (lines > 0),
    CONSTRAINT opening_checkpoint_group_once UNIQUE NULLS NOT DISTINCT
        (checkpoint_id, shape, purpose, owner_kind, counterparty, currency, scale, direction)
);

COMMENT ON TABLE accounting.opening_checkpoint_group IS
    'An opening checkpoint''s sums (P16-REM-003), in the three shapes the opening reads take: GL, LEDGER (no account joined) and COUNTERPARTY. Minor units summed exactly; written once with its checkpoint, never updated.';

GRANT SELECT, INSERT ON accounting.opening_checkpoint TO finapp_app;
GRANT SELECT, INSERT ON accounting.opening_checkpoint_group TO finapp_app;
GRANT SELECT ON accounting.opening_checkpoint TO finapp_reporting;
GRANT SELECT ON accounting.opening_checkpoint_group TO finapp_reporting;
