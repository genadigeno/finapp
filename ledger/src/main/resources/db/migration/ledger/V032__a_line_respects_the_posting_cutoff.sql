-- A journal LINE respects the posting cutoff too (P14-DOC-001, the Phase 14 exit review's financial audit, F1; INV-ACC-03,
-- INV-ACC-08, INV-HIST-01; ADR-0114).
--
-- WHAT WAS OPEN
--   V028 refuses a journal ENTRY dated on or before the posting cutoff, for every writer, raw SQL included. It did not
--   refuse a LINE: the application role may insert ledger.journal_line (V004's grant), and V004's balance trigger is
--   deferred and judges an entry's lines whenever they arrive. So a raw writer could append a balancing pair of lines to
--   an entry committed long ago and dated in a LOCKED or CLOSED month - the balance holds, no entry is inserted, and that
--   month's trial balance, GL and statements move after its close. No production path does it: every writer inserts an
--   entry and its lines in one transaction, through JdbcJournalEntryStore.append. The gate's clause - "the ledger refuses
--   posting_date <= closed_through at insert for every role and raw SQL" - was true of entries and not of lines.
--
-- WHAT THIS DOES
--   Every journal_line insert reads its entry's posting date and refuses it, as V028 refuses an entry, when that date is on
--   or before the cutoff: the same SQLSTATE LDCUT, the same fail-closed reading of a missing cutoff row, under the same
--   namespace 14 key 'cutoff' taken SHARED first - so a line serialises with a cutoff move exactly as an entry does (the
--   move takes the key exclusive), and under REPEATABLE READ or SERIALIZABLE the cutoff row is read FOR SHARE, a racing move
--   being a serialization failure rather than a stale read (V028's rule).
--
--   For an entry inserted in the same transaction - every production line - the entry's own insert already took the key and
--   passed the cutoff, so this re-judges what was just judged: the key is re-entrant for the holder, nothing can move the
--   cutoff before commit, and the answer is the same. The cost is one primary-key read of the entry and one read of the
--   cutoff's single row per line.
--
--   A line whose entry does not exist is left to V004's foreign key, which refuses it.
--
-- WHAT THIS DOES NOT DO
--   Appending a line to a committed entry dated in an OPEN period is not refused here; that is INV-HIST-01's question, not
--   the cutoff's, and no production path does it either.

CREATE OR REPLACE FUNCTION ledger.journal_line_respects_the_posting_cutoff()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    dated   date;
    cut     date;
    present boolean := false;
BEGIN
    -- The serialiser, as V028's entry trigger takes it: shared, before the cutoff is read.
    PERFORM pg_advisory_xact_lock_shared(14, hashtext('cutoff'));
    SELECT posting_date INTO dated FROM ledger.journal_entry WHERE id = NEW.entry_id;
    IF current_setting('transaction_isolation') = 'read committed' THEN
        SELECT closed_through, true INTO cut, present FROM ledger.posting_cutoff WHERE id = 1;
    ELSE
        SELECT closed_through, true INTO cut, present FROM ledger.posting_cutoff WHERE id = 1 FOR SHARE;
    END IF;
    IF NOT coalesce(present, false) THEN
        RAISE EXCEPTION 'ledger_posting_cutoff_missing: line % is refused - the posting cutoff row is missing (INV-ACC-08)',
                NEW.id
            USING ERRCODE = '23514';
    END IF;
    IF dated IS NOT NULL AND cut IS NOT NULL AND dated <= cut THEN
        RAISE EXCEPTION 'ledger_posting_cutoff: line % joins entry % dated % - on or before the posting cutoff %; the period is locked (INV-ACC-03, INV-ACC-08)',
                NEW.id, NEW.entry_id, dated, cut
            USING ERRCODE = 'LDCUT';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_line_respects_the_posting_cutoff
    BEFORE INSERT ON ledger.journal_line
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_line_respects_the_posting_cutoff();
