-- Make terminal idempotency claims terminal, and their identity immutable.
--
-- INVARIANTS THIS MIGRATION ENFORCES
--   INV-LIFE-04  Terminal states are terminal. Once a claim is COMPLETED or FAILED, no
--                transition out of it occurs.
--   INV-IDEM-01  A retry returns the original outcome. That is only true if the stored outcome
--                cannot be rewritten after the fact.
--   INV-IDEM-03  Key reuse with a different request is rejected by comparing fingerprints.
--                That comparison is defeated if the stored fingerprint can be changed.
--
-- WHY A TRIGGER AND NOT A CHECK CONSTRAINT
--   A CHECK constraint sees only the row being written. It cannot express "this row may not
--   change from what it was", because it has no access to the previous value. V002's checks
--   therefore constrain the *shape* of a row and say nothing about transitions between rows.
--
--   The gap that leaves is not theoretical. It was found by running, against the developer
--   database, exactly the statement an operator or a defective wrapper would run:
--
--       UPDATE platform.idempotency_record SET state='IN_PROGRESS', completed_at=NULL ...
--
--   which succeeded and turned a finished command back into an unfinished one. A wrapper
--   reading that row would find a claim whose outcome is unknown and re-execute the command:
--   a second financial effect, produced by an UPDATE that no application code performed.
--   DATA_MIGRATIONS.md §6 makes this argument generally — the next caller is a job, an
--   operator tool, or a psql session — and it applies here whether or not application code is
--   correct.
--
-- WHAT REMAINS THE APPLICATION'S JOB
--   This forbids leaving a terminal state and changing a claim's identity. It does not encode
--   the full transition table: which transitions out of IN_PROGRESS are legitimate, how long a
--   claim may remain IN_PROGRESS, and when a crashed claim may be reclaimed are decisions
--   P0-TSK-016 owns, because they depend on timeouts and on the command being executed. The
--   database enforces the part that is true regardless of any of that.

CREATE FUNCTION platform.idempotency_record_guard_transitions()
    RETURNS trigger
    LANGUAGE plpgsql
AS
$$
BEGIN
    -- Identity and the request it stands for never change, in any state. Rewriting the
    -- fingerprint would make INV-IDEM-03's comparison compare the new request against itself
    -- and always agree; rewriting the key would move a claim to a different command.
    IF NEW.scope IS DISTINCT FROM OLD.scope
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
        OR NEW.fingerprint_algorithm IS DISTINCT FROM OLD.fingerprint_algorithm
    THEN
        RAISE EXCEPTION
            'idempotency_record identity is immutable (INV-IDEM-03)'
            USING ERRCODE = '23514';
    END IF;

    -- A terminal claim is frozen entirely. Not merely its state: the stored response is what a
    -- retry replays, so an editable response means INV-IDEM-01's "returns the original outcome"
    -- is a promise nothing keeps.
    IF OLD.state <> 'IN_PROGRESS' THEN
        RAISE EXCEPTION
            'idempotency_record % is terminal in state % and cannot be modified (INV-LIFE-04)',
            OLD.idempotency_key, OLD.state
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION platform.idempotency_record_guard_transitions() IS
    'Rejects any update that leaves a terminal state or alters a claim''s identity. A CHECK '
    'constraint cannot express either, because it cannot see the previous row.';

CREATE TRIGGER idempotency_record_guard_transitions
    BEFORE UPDATE
    ON platform.idempotency_record
    FOR EACH ROW
EXECUTE FUNCTION platform.idempotency_record_guard_transitions();

-- Deletion is deliberately not guarded. Retention removes expired records, and that is the
-- documented lifecycle (DATA_MIGRATIONS.md §8) rather than a loss of history: an idempotency
-- record is a concurrency-control artefact, and the immutable record of what a command did
-- lives in the ledger. Guarding DELETE would make the expiry policy unimplementable.
