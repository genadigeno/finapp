-- finapp-contract: first-conforming-release=ANY; waives=TRIGGER; reason=the trigger only stamps the new txid column, which has a default, and refuses no write: every release's journal_line INSERT names its own columns and stays valid, the stamp filled whatever it supplies
-- The merchant payable read bounded by a commit-order checkpoint (P16-REM-001; ADR-0133 decision 3; THREAT_MODEL.md section 6
-- item 55 (i), placed by the owner on 2026-11-13).
--
-- WHAT THIS IS FOR
--   GET /v1/merchant/payable folded every line of the merchant payable account on every call, so its cost grew with the merchant's
--   age: E30's first limit at three replicas, and a resource-exhaustion residual (T14). A checkpoint is the fold of one account's lines
--   below a watermark W, where W is pg_snapshot_xmin of the snapshot that read them: every transaction below W has finished, every
--   committed line of one was visible to that read, and none can ever be added below W afterwards (V034's argument, for the census).
--   A read is then the latest valid checkpoint plus the lines at or above its watermark (CheckpointedPositionBreakdown).
--
-- THE LINE'S STAMP
--   The index the read needs is by account and commit order, so the line carries its entry's top-level transaction itself. Stamped,
--   not offered, exactly as V034 stamps the entry: the BEFORE INSERT trigger overwrites whatever a writer supplied, reads no clock,
--   takes the top-level id from inside a savepoint, and fires last of journal_line's BEFORE INSERT triggers (they fire in name order:
--   journal_line_account_accepts_postings, journal_line_respects_the_posting_cutoff, journal_line_reversal_is_bounded), so a refused
--   line assigns nothing. A line is inserted in its entry's transaction, so its stamp is its entry's. UPDATE and DELETE stay refused
--   by journal_line_is_append_only (V004).
--
-- EXISTING ROWS, THE LOCKS AND THE BUILD
--   pg_current_xact_id() is STABLE, so the default is evaluated once and stored as the column's missing value: every existing line reads
--   this migration's xid and no row is rewritten. Each lies below any watermark a later read can take, so every checkpoint folds them,
--   which is right. ADD COLUMN takes ACCESS EXCLUSIVE on the platform's hottest table for its moment; CREATE TRIGGER takes SHARE ROW
--   EXCLUSIVE; the index build holds SHARE, blocking every posting for as long as it reads the table. No production journal exists yet,
--   so none of it costs anything today; a journal that grows large before this runs wants the index built CONCURRENTLY by hand first,
--   under this name and on these columns, which IF NOT EXISTS then keeps (OPERATIONS_RUNBOOK.md 11.48). The index is one more write on every line inserted: E38 and E39 measure that on the
--   posting path when they run (POSTPONED_ISSUES.md PI-025).
--
-- THE CHECKPOINT
--   Derived data beside the journal, never the journal: deleting every row changes no figure, only the read's cost. A row names what
--   its buckets are a fold OF - the account, the classification rules' version (a change to the rules retires every checkpoint), the
--   caller's precedence and namespaces - and WHERE: the cluster it was taken on (pg_control_system()'s system_identifier). A logical
--   restore restarts the transaction counter below the restored txids (V034's warning), so a checkpoint is read only on its own cluster.
--   One row per watermark: ten instances reaching the same one store it once (ON CONFLICT DO NOTHING); different watermarks are
--   different rows, each correct. The application role may read and add checkpoints, never change or remove one.
--
-- A LOGICAL RESTORE
--   Restored checkpoints carry the old cluster's identity and are never read; the first reads fold whole and store new ones. Restored
--   lines keep their dumped txids (the triggers are created after the data). OPERATIONS_RUNBOOK.md 11.48.

ALTER TABLE ledger.journal_line
    ADD COLUMN txid xid8 NOT NULL DEFAULT pg_catalog.pg_current_xact_id();

COMMENT ON COLUMN ledger.journal_line.txid IS
    'The top-level transaction that inserted this line, its entry''s: a position in commit order that no clock and no writer can choose, stamped by journal_line_stamps_its_transaction. A checkpoint folds the lines below pg_snapshot_xmin.';

CREATE OR REPLACE FUNCTION ledger.journal_line_stamps_its_transaction()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    NEW.txid := pg_catalog.pg_current_xact_id();
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_line_stamps_its_transaction
    BEFORE INSERT ON ledger.journal_line
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_line_stamps_its_transaction();

CREATE INDEX IF NOT EXISTS journal_line_by_account_and_transaction ON ledger.journal_line (ledger_account_id, txid);

CREATE TABLE ledger.position_checkpoint (
    id                     uuid     NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,

    ledger_account_id      uuid     NOT NULL REFERENCES ledger.ledger_account (id),

    -- CheckpointedPositionBreakdown.CLASSIFICATION_VERSION when it was taken.
    classification_version integer  NOT NULL,

    -- The caller's counterparty purposes and posting-key namespaces, in the caller's order.
    precedence             text[]   NOT NULL,
    namespaces             text[]   NOT NULL,

    -- pg_control_system()'s system_identifier: the cluster the watermark is a position of.
    cluster_identity       text     NOT NULL,

    -- Every line of the account below it is folded here, and no line can be added below it.
    watermark              xid8     NOT NULL,

    -- How many lines the buckets fold, for the reader's threshold.
    lines                  bigint   NOT NULL,

    CONSTRAINT position_checkpoint_version_is_positive
        CHECK (classification_version > 0),

    CONSTRAINT position_checkpoint_lines_are_counted
        CHECK (lines > 0),

    CONSTRAINT position_checkpoint_one_per_watermark
        UNIQUE (ledger_account_id, classification_version, precedence, namespaces, cluster_identity, watermark)
);

COMMENT ON TABLE ledger.position_checkpoint IS
    'A fold of one account''s journal lines below a commit-order watermark, read by the merchant API''s payable read with the lines since (P16-REM-001, ADR-0133 decision 3). Derived data, never the journal: deleting every row changes no figure.';

CREATE TABLE ledger.position_checkpoint_bucket (
    checkpoint_id             uuid     NOT NULL REFERENCES ledger.position_checkpoint (id),

    -- BucketFold.key: the direction, the namespace, and the origin or what the line faced.
    bucket                    text     NOT NULL,

    direction                 text     NOT NULL,
    counterparty              text,
    reconciliation_attributed boolean  NOT NULL,
    accounting_origin         boolean  NOT NULL,
    namespace                 text,

    amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL, CHECK (currency ~ '^[A-Z]{3}$'), CHECK (scale BETWEEN 0 AND 9),

    PRIMARY KEY (checkpoint_id, bucket),

    CONSTRAINT position_checkpoint_bucket_direction_is_known
        CHECK (direction IN ('DEBIT', 'CREDIT')),

    CONSTRAINT position_checkpoint_bucket_amount_is_positive
        CHECK (amount_minor > 0)
);

COMMENT ON TABLE ledger.position_checkpoint_bucket IS
    'One bucket of a position checkpoint: the total of the account''s lines below its watermark that fold into it.';

GRANT SELECT, INSERT ON ledger.position_checkpoint TO finapp_app;
GRANT SELECT, INSERT ON ledger.position_checkpoint_bucket TO finapp_app;
