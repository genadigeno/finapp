-- Reversal: a new effect referencing the original (P3-TSK-016, INV-REV-01, INV-REV-02).
--
-- INV-REV-01's two halves at the schema: the REFERENCE is the new column below with its
-- implication CHECK - a reversal references its original, and nothing else may - while the
-- IMMUTABILITY of the original needs nothing new, because P3-TSK-005 already carries it at
-- DB-PRIVILEGE (no UPDATE, no DELETE, the append-only trigger binding even the migrator).
-- Reversal earns no exception to that; it is the reason the regime exists.
--
-- THE BOUND'S ARBITER, AND WHY IT IS AN ADVISORY LOCK IN A TRIGGER
--   INV-REV-02 spans rows that do not exist yet: "this reversal plus every prior reversal
--   never exceeds the original" is a sum over sibling REVERSAL entries, and two concurrent
--   reversal INSERTs cannot see each other's uncommitted rows under READ COMMITTED - a
--   predicate in the INSERT's own statement re-evaluates against the statement snapshot and
--   both pass (the P2-TSK-015 write-skew shape). The row-lock arbiters are unavailable BY
--   THE PHASE'S OWN DESIGN: the application role holds no UPDATE on journal_entry, so
--   SELECT ... FOR UPDATE on the original is impossible, and a mutable reversed-total row
--   would be a second authority for a number the immutable rows already define. So the
--   BEFORE INSERT trigger takes pg_advisory_xact_lock(2, hashtext(original)) - namespace 2,
--   registered in DISTRIBUTED_EXECUTION.md beside the relay's - and sums committed reversal
--   lines under it. Held to commit, for EVERY writer: two reversals of one original
--   serialize, and the loser re-judges what the winner committed. Reversals of different
--   originals do not contend (hash collisions cost throughput, never correctness).
--
-- The refusal is ERRCODE 23514 with a stable marker (ledger_reversal_is_bounded), translated
-- by the store to the named OverReversalException - the V007 pattern. Messages name the
-- entry, the account and the pair - identifiers and enumerated names, never an amount
-- (INV-AUD-02).

ALTER TABLE ledger.journal_entry
    ADD COLUMN reverses_entry_id uuid REFERENCES ledger.journal_entry (id);

ALTER TABLE ledger.journal_entry
    ADD CONSTRAINT journal_entry_reversal_references_its_original
        CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL));

ALTER TABLE ledger.journal_entry
    ADD CONSTRAINT journal_entry_reversal_is_not_its_own_original
        CHECK (reverses_entry_id <> id);

-- The bound's read: every reversal of one original. Partial - POSTING and ADJUSTMENT rows
-- never carry the column, and must not grow the index.
CREATE INDEX journal_entry_reversals_of
    ON ledger.journal_entry (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;

-- A reversal's original is never itself a REVERSAL: a correction of a correction is a new
-- posting or adjustment, because a chain would make the bound's subject ambiguous. The FK
-- gives existence; this trigger gives the kind. The domain refuses too - this binds the
-- writers the domain never sees.
CREATE OR REPLACE FUNCTION ledger.journal_entry_reversal_references_a_posting()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    original_type text;
BEGIN
    IF NEW.reverses_entry_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT entry_type INTO original_type
        FROM ledger.journal_entry
        WHERE id = NEW.reverses_entry_id;
    IF original_type = 'REVERSAL' THEN
        RAISE EXCEPTION 'ledger_reversal_of_reversal: entry % may not reverse reversal % - a correction of a correction is a new posting or adjustment (P3-TSK-016)',
                NEW.id, NEW.reverses_entry_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entry_reversal_references_a_posting
    BEFORE INSERT ON ledger.journal_entry
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_entry_reversal_references_a_posting();

-- INV-REV-02 at DB-CONSTRAINT rank: per (account, direction) pair, this line plus every
-- prior reversal line at the pair never exceeds the original's total at the OPPOSITE
-- direction. Scale-guarded - summing raw minor units across scales is meaningless (the V004
-- balance trigger's own discipline) - and the SQL sums are admissible where a history-wide
-- SUM was not (P3-TSK-008): each term is positive and inductively bounded by the original's
-- own bigint, so no overflow, and the scale condition refuses the cross-scale case wholly.
CREATE OR REPLACE FUNCTION ledger.journal_line_reversal_is_bounded()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    original       uuid;
    original_total bigint;
    original_scale smallint;
    reversed_total bigint;
BEGIN
    SELECT reverses_entry_id INTO original
        FROM ledger.journal_entry
        WHERE id = NEW.entry_id;
    IF original IS NULL THEN
        RETURN NEW;  -- not a reversal line; the bound has no subject
    END IF;

    -- The serializer (see header): every writer reversing this original queues here, and a
    -- resumed loser's reads below see what the winner committed.
    PERFORM pg_advisory_xact_lock(2, hashtext(original::text));

    SELECT sum(line.amount_minor), min(line.scale)
        INTO original_total, original_scale
        FROM ledger.journal_line line
        WHERE line.entry_id = original
          AND line.ledger_account_id = NEW.ledger_account_id
          AND line.direction = CASE NEW.direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END;
    IF original_total IS NULL THEN
        RAISE EXCEPTION 'ledger_reversal_is_bounded: entry % reverses nothing on account % at % - the pair mirrors no original line (P3-TSK-016)',
                NEW.entry_id, NEW.ledger_account_id, NEW.direction
            USING ERRCODE = '23514';
    END IF;
    IF original_scale <> NEW.scale THEN
        RAISE EXCEPTION 'ledger_reversal_is_bounded: entry % reverses account % at a different scale than the original persisted (INV-MON-03)',
                NEW.entry_id, NEW.ledger_account_id
            USING ERRCODE = '23514';
    END IF;

    SELECT coalesce(sum(line.amount_minor), 0)
        INTO reversed_total
        FROM ledger.journal_line line
        JOIN ledger.journal_entry sibling ON sibling.id = line.entry_id
        WHERE sibling.reverses_entry_id = original
          AND line.ledger_account_id = NEW.ledger_account_id
          AND line.direction = NEW.direction;

    IF reversed_total + NEW.amount_minor > original_total THEN
        RAISE EXCEPTION 'ledger_reversal_is_bounded: entry % would over-reverse entry % on account % (INV-REV-02)',
                NEW.entry_id, original, NEW.ledger_account_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_line_reversal_is_bounded
    BEFORE INSERT ON ledger.journal_line
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_line_reversal_is_bounded();

COMMENT ON COLUMN ledger.journal_entry.reverses_entry_id IS
    'The original this REVERSAL compensates (INV-REV-01); NULL on every other kind, by '
    'CHECK. The bound - this reversal plus every prior one never exceeds the original, per '
    '(account, direction) pair - is judged by the journal_line trigger under an advisory '
    'lock on this identifier (INV-REV-02).';

-- No grant changes: the application role''s existing INSERT covers the new column, and the
-- absence of UPDATE/DELETE is the point (INV-REV-01, INV-HIST-01).
