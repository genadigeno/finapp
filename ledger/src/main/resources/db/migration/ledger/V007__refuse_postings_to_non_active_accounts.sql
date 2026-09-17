-- A non-ACTIVE ledger account accepts no postings (P3-TSK-014).
--
-- "Closing an account closes the agreement and not the history" has two halves, and this is the
-- half the schema can carry: from the moment an account leaves ACTIVE, no journal line lands on
-- it - for EVERY writer, the domain, raw SQL, an operator and code nobody has written yet (the
-- P3-TSK-005 class of enforcement). The domain's own refusal exists too; this binds the writers
-- the domain never sees.
--
-- WHY THE STATUS READ TAKES `FOR KEY SHARE`, AND THAT IS THE WHOLE DESIGN
--   P3-TSK-006 recorded the trap in as many words: "a lock-free status read here would be the
--   check that passes every test and loses the race to close." A bare SELECT inside this
--   trigger would read the pre-close row while a close sits uncommitted, pass, and let the
--   insert complete after the close commits - a posting landed on a closed account, with every
--   sequential test green. Locked with FOR KEY SHARE, the read CONFLICTS with the closer's
--   SELECT ... FOR UPDATE (P3-TSK-014's close takes exactly that, because a plain status
--   UPDATE's FOR NO KEY UPDATE would not conflict with the FK's FOR KEY SHARE): the read
--   blocks until the close commits or rolls back, and under READ COMMITTED a lock-wait
--   re-fetches the LATEST committed row - so the trigger judges the status the close actually
--   left, whichever side got there first.
--
-- The refusal is ERRCODE 23514 (check_violation) with a stable marker
-- (ledger_account_accepts_postings), so the store can translate it to a named domain refusal
-- without matching prose. The message names the account and its status - identifiers and an
-- enumerated name, never an amount (INV-AUD-02).

CREATE OR REPLACE FUNCTION ledger.journal_line_account_accepts_postings()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account_status text;
BEGIN
    SELECT status INTO account_status
        FROM ledger.ledger_account
        WHERE id = NEW.ledger_account_id
        FOR KEY SHARE;

    -- An absent account is the FK's refusal to make, not this trigger's.
    IF account_status IS NOT NULL AND account_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'ledger_account_accepts_postings: account % is % and accepts no postings (P3-TSK-014)',
                NEW.ledger_account_id, account_status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_line_account_accepts_postings
    BEFORE INSERT ON ledger.journal_line
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_line_account_accepts_postings();
