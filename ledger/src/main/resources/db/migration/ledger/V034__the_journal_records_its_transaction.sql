-- The journal's commit-order position (P15-TSK-035's fix round; ADR-0014: no correctness argument may depend on clock skew being
-- small; THREAT_MODEL.md T15).
--
-- WHAT THIS IS FOR
--   journal_entry.id is a UUIDv7 minted on an instance's clock, and a holder of the application's credential can insert any id at all.
--   So an id says nothing about commit order: a cursor over ids can be passed by an entry minted behind it, which is then never read
--   by anything that walks the journal in id order. txid is the id of the top-level transaction that inserted the row. Together with
--   pg_snapshot_xmin(pg_current_snapshot()) it gives a position with no clock in it: every txid below a snapshot's xmin is finished,
--   and every row of such a transaction that committed is visible to that snapshot. Nothing can be inserted behind a position taken
--   below xmin afterwards, because a transaction that could do so would hold an xid below xmin, and xmin is by definition the oldest
--   one still running. The source-join census walks (txid, id).
--
-- STAMPED, NOT OFFERED
--   The default alone would leave the column in the writer's hands: a raw INSERT names any txid it likes and lands behind the walk.
--   The BEFORE INSERT trigger overwrites whatever was supplied, for every writer. It reads no clock and takes the top-level id even
--   from inside a savepoint. It fires last of the BEFORE triggers (they fire in name order; the table's are
--   journal_entry_respects_the_posting_cutoff and journal_entry_reversal_references_a_posting), so a refused entry assigns nothing
--   and nothing after it can change the stamp. BEFORE UPDATE OR DELETE is already refused by journal_entry_is_append_only (V004).
--
-- WHAT IT DOES NOT CHANGE
--   No poster, no statement, no judgement: a posting's INSERT still names its own columns and this one fills itself. The role grants
--   are per table and cover the new column.
--
-- EXISTING ROWS AND THE LOCK
--   pg_current_xact_id() is STABLE, so PostgreSQL evaluates this default once and stores it as the column's missing value: every
--   existing row reads the migration's own xid (so they sort among themselves by id, which is right) and no row is rewritten. ADD COLUMN
--   still takes ACCESS EXCLUSIVE for its moment, and the index build holds SHARE; no production journal exists yet, so neither costs
--   anything today. A journal that grows large before this runs would want its index built CONCURRENTLY by hand first.
--
-- A LOGICAL RESTORE
--   xids are not portable: a dump and restore into a new cluster restarts the counter below the restored rows' txids. The census refuses
--   to walk in that state (its head would be ahead of the database's counter) rather than skip silently; OPERATIONS_RUNBOOK.md 11.27.

ALTER TABLE ledger.journal_entry
    ADD COLUMN txid xid8 NOT NULL DEFAULT pg_catalog.pg_current_xact_id();

COMMENT ON COLUMN ledger.journal_entry.txid IS
    'The top-level transaction that inserted this entry: a position in commit order that no clock and no writer can choose, stamped by journal_entry_stamps_its_transaction. Walk by (txid, id) with txid below pg_snapshot_xmin.';

CREATE OR REPLACE FUNCTION ledger.journal_entry_stamps_its_transaction()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    NEW.txid := pg_catalog.pg_current_xact_id();
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entry_stamps_its_transaction
    BEFORE INSERT ON ledger.journal_entry
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_entry_stamps_its_transaction();

CREATE INDEX journal_entry_by_transaction ON ledger.journal_entry (txid, id);
