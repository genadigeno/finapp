-- P8-TSK-022: the readmission rule - a readmission is never a way round the second person
-- (INV-SET-07; ADR-0066 section 8; the Phase 7 -> 8 transition's re-check, R5).
--
-- WHY A TRIGGER, AND WHY NOW
--   V002 holds an upload to its second person by CHECKs on its own row: attested_by differs
--   from received_by, and an ACCEPTED upload is attested. A readmission's rule is CROSS-ROW -
--   what it inherits, and from whom its attester must differ, live on the files it readmits -
--   and a CHECK cannot read another row. V002 stated the gap and left it to this task.
--
-- WHAT A READMISSION INHERITS
--   A readmission shares its original's checksum, so it inherits the original's authentication
--   when the original was pulled (its source's credential) or attested (its second person) - or,
--   when the original is itself a readmission, whatever that one inherited. A DECLINED original
--   passes NOTHING on: declining is a person's judgement against the file, so a readmission of
--   it (admissible since P8-TSK-022's design decided ADR-0066 section 8's recorded question)
--   needs a second person of its own, however the original was authenticated.
--
-- WHO MAY ATTEST ONE THAT INHERITS NOTHING
--   A second person to EVERY submitter along the chain: not the readmitter, not any earlier
--   readmitter, not the original's uploader. Otherwise an uploader could readmit their own
--   rejected, never-attested file and attest the readmission - one person, accepted evidence.
--
-- ONE SOURCE OF TRUTH FOR BOTH RANKS
--   settlement.file_inherits_authentication and settlement.file_submitters are what this
--   trigger refuses by AND what the accept leg's eligibility predicate claims by
--   (JdbcSettlementFileStore.ELIGIBLE): the claim and the refusal cannot drift apart. The walk
--   lives in settlement.file_authenticates_readmission, over the ORIGINAL's id, so the trigger
--   can judge a row being INSERTED - which no read by the readmission's own id could see.
--
-- Every function is SECURITY INVOKER (the default): it reads only what its caller may read,
-- and finapp_app already holds SELECT on settlement.file. The walks are bounded: the schema
-- builds no cycle (readmits_file_id names an existing row and is frozen), but a hand-made one
-- fails CLOSED - nothing inherited, nothing looped - rather than erroring every claim read.

-- ---------------------------------------------------------------------------------------------
-- Whether a file passes its authentication on to a readmission of it.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION settlement.file_authenticates_readmission(original_id UUID)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $$
    -- Walk outward from the original: stop at the first DECLINED file (nothing passes), at the
    -- first pulled or attested one (it authenticates), or at a root that is neither.
    WITH RECURSIVE chain (depth, readmits_file_id, received_via, attested, declined) AS (
        SELECT 1, f.readmits_file_id, f.received_via, f.attested_by IS NOT NULL,
               f.rejection_code IS NOT DISTINCT FROM 'DECLINED'
          FROM settlement.file f
         WHERE f.id = file_authenticates_readmission.original_id
        UNION ALL
        SELECT c.depth + 1, f.readmits_file_id, f.received_via, f.attested_by IS NOT NULL,
               f.rejection_code IS NOT DISTINCT FROM 'DECLINED'
          FROM chain c
          JOIN settlement.file f ON f.id = c.readmits_file_id
         WHERE c.received_via = 'READMISSION'
           AND NOT c.declined
           AND NOT c.attested
           AND c.depth < 1000
    )
    SELECT COALESCE(
        (SELECT NOT c.declined AND (c.received_via = 'PULL' OR c.attested)
           FROM chain c
          ORDER BY c.depth DESC
          LIMIT 1),
        false);
$$;

-- ---------------------------------------------------------------------------------------------
-- Whether a readmission inherits its original's authentication.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION settlement.file_inherits_authentication(readmission_id UUID)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $$
    SELECT COALESCE(
        (SELECT settlement.file_authenticates_readmission(r.readmits_file_id)
           FROM settlement.file r
          WHERE r.id = file_inherits_authentication.readmission_id
            AND r.received_via = 'READMISSION'),
        false);
$$;

-- Adjacent literals separated by a newline are one constant (the SQL standard's continuation).
COMMENT ON FUNCTION settlement.file_inherits_authentication(UUID) IS
    'Whether a READMISSION inherits its original''s authentication (P8-TSK-022, INV-SET-07):'
    ' the original was pulled or attested, or is itself a readmission that inherited - and'
    ' was not DECLINED, which passes nothing on. False for any other file.';

-- ---------------------------------------------------------------------------------------------
-- Every person who submitted the file's bytes, along its readmission chain.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION settlement.file_submitters(file_id UUID)
    RETURNS SETOF text
    LANGUAGE sql
    STABLE
AS $$
    WITH RECURSIVE chain (depth, readmits_file_id, received_by) AS (
        SELECT 1, f.readmits_file_id, f.received_by
          FROM settlement.file f
         WHERE f.id = file_submitters.file_id
        UNION ALL
        SELECT c.depth + 1, f.readmits_file_id, f.received_by
          FROM chain c
          JOIN settlement.file f ON f.id = c.readmits_file_id
         WHERE c.depth < 1000
    )
    SELECT DISTINCT c.received_by FROM chain c WHERE c.received_by IS NOT NULL;
$$;

COMMENT ON FUNCTION settlement.file_submitters(UUID) IS
    'Every received_by from the file back to the root of its readmission chain - the'
    ' readmitters and the original''s uploader; a pull has none (P8-TSK-022). A'
    ' readmission''s attester is none of them (INV-SET-07).';

-- ---------------------------------------------------------------------------------------------
-- The trigger: the readmission rule for every writer, on birth and on every move.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION settlement.file_readmission_is_authenticated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    submitters TEXT[];
BEGIN
    IF NEW.received_via IS DISTINCT FROM 'READMISSION'
            OR (NEW.attested_by IS NULL AND NEW.status IS DISTINCT FROM 'ACCEPTED') THEN
        RETURN NEW;
    END IF;
    -- The readmitter, then everyone back to the root - by the ORIGINAL's id, so a row being
    -- inserted is judged as surely as one being updated.
    submitters := array_prepend(
        NEW.received_by,
        ARRAY(SELECT settlement.file_submitters(NEW.readmits_file_id)));
    -- file_attester_is_second_person's rank, extended across the chain.
    IF NEW.attested_by = ANY (submitters) THEN
        RAISE EXCEPTION USING MESSAGE =
            'a readmission''s attester is a second person to every submitter - not the'
            || ' readmitter, no earlier readmitter, not the original''s uploader'
            || ' (INV-SET-07, P8-TSK-022)';
    END IF;
    -- file_accepted_upload_is_attested's rank, for a readmission whose original passes nothing.
    IF NEW.status = 'ACCEPTED'
            AND NEW.attested_by IS NULL
            AND NOT settlement.file_authenticates_readmission(NEW.readmits_file_id) THEN
        RAISE EXCEPTION USING MESSAGE =
            'a readmission that inherits no authentication is ACCEPTED only once a second'
            || ' person attested it (INV-SET-07, P8-TSK-022)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER file_readmission_is_authenticated
    BEFORE INSERT OR UPDATE ON settlement.file
    FOR EACH ROW
    EXECUTE FUNCTION settlement.file_readmission_is_authenticated();
