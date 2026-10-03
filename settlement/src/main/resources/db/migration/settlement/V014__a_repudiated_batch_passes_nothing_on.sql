-- The Phase 8 -> 9 transition's re-gate, NEW-SEC-1: a repudiated batch's file passes NOTHING on
-- (INV-SET-07; ADR-0066 section 8, ADR-0065 section 10).
--
-- WHAT THE RE-GATE FOUND
--   V011 made an ACCEPTED file whose batch is REPUDIATED readmissible (MI-2) - and V009's
--   unchanged walk judged that original as it judges any accepted file: pulled or attested, so
--   it AUTHENTICATES its readmission. One holder of RECONCILIATION_ADMINISTER could then undo a
--   four-eyes verdict alone: readmit the repudiated batch's file, and the accept leg re-accepts
--   it with no second person - the repudiated recognition re-posted on one person's act, with
--   nothing distinguishing the mis-normalisation case (bytes genuine) from the fabrication case
--   the repudiation exists for.
--
-- WHAT THIS MIGRATION ENFORCES, FOR EVERY WRITER
--   The walk gains the one bar an approved repudiation is: a file whose batch is REPUDIATED
--   passes NOTHING on - exactly the DECLINED rule, because each is a person's judgement against
--   the file's effect. Its readmission waits for its own attestation by a person distinct from
--   every submitter along the chain (V009's trigger and ranks, unchanged, read this function),
--   so reinstating a repudiated batch takes two people again: the readmitter and the attester.
--   A REPUDIATED batch is terminal (V010), so the bar cannot go stale; and a readmission of an
--   ACCEPTED original exists only once its batch is REPUDIATED (V011), so no standing row's
--   answer moves. The trigger, the accept leg's eligibility and the attestation's domain rank
--   all read this one function: no writer re-accepts what the domain refuses.

-- ---------------------------------------------------------------------------------------------
-- Whether a file passes its authentication on to a readmission of it - V009's walk, re-stated
-- whole with the repudiation bar beside the decline's.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION settlement.file_authenticates_readmission(original_id UUID)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $$
    -- Walk outward from the original: stop at the first file that passes nothing on - DECLINED,
    -- or its batch REPUDIATED (NEW-SEC-1) - at the first pulled or attested one (it
    -- authenticates), or at a root that is neither.
    WITH RECURSIVE chain (depth, readmits_file_id, received_via, attested, barred) AS (
        SELECT 1, f.readmits_file_id, f.received_via, f.attested_by IS NOT NULL,
               f.rejection_code IS NOT DISTINCT FROM 'DECLINED'
                   OR EXISTS (SELECT 1 FROM settlement.batch b
                               WHERE b.file_id = f.id AND b.status = 'REPUDIATED')
          FROM settlement.file f
         WHERE f.id = file_authenticates_readmission.original_id
        UNION ALL
        SELECT c.depth + 1, f.readmits_file_id, f.received_via, f.attested_by IS NOT NULL,
               f.rejection_code IS NOT DISTINCT FROM 'DECLINED'
                   OR EXISTS (SELECT 1 FROM settlement.batch b
                               WHERE b.file_id = f.id AND b.status = 'REPUDIATED')
          FROM chain c
          JOIN settlement.file f ON f.id = c.readmits_file_id
         WHERE c.received_via = 'READMISSION'
           AND NOT c.barred
           AND NOT c.attested
           AND c.depth < 1000
    )
    SELECT COALESCE(
        (SELECT NOT c.barred AND (c.received_via = 'PULL' OR c.attested)
           FROM chain c
          ORDER BY c.depth DESC
          LIMIT 1),
        false);
$$;

-- Adjacent literals separated by a newline are one constant (the SQL standard's continuation).
COMMENT ON FUNCTION settlement.file_authenticates_readmission(UUID) IS
    'Whether a file passes its authentication on to a readmission of it (P8-TSK-022,'
    ' INV-SET-07): pulled or attested, walked through intermediate readmissions. A DECLINED'
    ' file - or one whose batch is REPUDIATED (the Phase 8 -> 9 transition''s re-gate,'
    ' NEW-SEC-1) - passes NOTHING on: each is a person''s judgement against the file''s effect.';

COMMENT ON FUNCTION settlement.file_inherits_authentication(UUID) IS
    'Whether a READMISSION inherits its original''s authentication (P8-TSK-022, INV-SET-07):'
    ' the original was pulled or attested, or is itself a readmission that inherited - and'
    ' was not DECLINED and its batch is not REPUDIATED, each of which passes nothing on (the'
    ' Phase 8 -> 9 transition''s re-gate, NEW-SEC-1). False for any other file.';
