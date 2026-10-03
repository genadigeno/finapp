-- The Phase 8 -> 9 transition: an ACCEPTED file whose batch is REPUDIATED is readmissible - the
-- recovery ADR-0065 section 10 names for our own adapter's mis-normalisation (MI-2; INV-SET-07,
-- INV-HIST-02; ADR-0066 section 8).
--
-- WHAT THE TRANSITION'S GATE FOUND
--   A batch our own adapter mis-normalised is repudiated (P8-TSK-023): its recognition reversed,
--   its allocations countered, its remittance closed - and its FILE stays ACCEPTED, the bytes
--   retained byte-identical (V010). But the genuine evidence IS those bytes, and they could never
--   be parsed again: a re-upload or a pull of them converges on the ACCEPTED file's content
--   address (V002's file_content_address), and readmission - "the only way the same bytes are
--   parsed again" (ADR-0066 section 8) - admitted only a REJECTED original. The recovery the ADR
--   promised ("the genuine file is then re-presented and accepted normally") did not exist.
--
-- WHAT THIS MIGRATION ENFORCES, FOR EVERY WRITER
--   The readmission rule gains the one cross-row fact the domain's FileReadmission now judges by,
--   so no writer readmits what the domain refuses: a READMISSION row is born naming an original
--   that is either
--     * REJECTED with a readmissible verdict - every verdict but SOURCE_RETIRED (a re-opened
--       source is a NEW source, its re-issue arriving through its own door), or
--     * ACCEPTED, with its batch REPUDIATED - the evidence's verdict withdrawn by an approved
--       four-eyes repudiation, the file itself untouched.
--   Never a file still in its machine, never an accepted file whose batch stands.
--
-- WHAT A READMISSION OF A REPUDIATED BATCH'S FILE INHERITS
--   Its original's authentication, by V009's unchanged walk: an ACCEPTED file was pulled, or was
--   an attested upload, or a readmission that inherited - so it authenticates its readmission,
--   and the accept leg claims the readmission with no second attestation (the repudiation's two
--   people already judged the bytes' fate; the readmission's reason names why they are parsed
--   again). The parse leg's live uniques stay the arbiter of identity: a REPUDIATED batch frees
--   its (source, reference, currency) and its statement sequence (V010).

CREATE OR REPLACE FUNCTION settlement.file_readmits_a_recoverable_original()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.received_via IS DISTINCT FROM 'READMISSION' THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
            SELECT 1
              FROM settlement.file o
             WHERE o.id = NEW.readmits_file_id
               AND ((o.status = 'REJECTED'
                     AND o.rejection_code IS DISTINCT FROM 'SOURCE_RETIRED')
                    OR (o.status = 'ACCEPTED'
                        AND EXISTS (
                            SELECT 1 FROM settlement.batch b
                             WHERE b.file_id = o.id AND b.status = 'REPUDIATED')))) THEN
        RAISE EXCEPTION USING MESSAGE =
            'file_readmits_a_recoverable_original: a readmission names a REJECTED original'
            || ' (never SOURCE_RETIRED) or an ACCEPTED one whose batch is REPUDIATED'
            || ' (INV-SET-07, ADR-0065 section 10)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER file_readmits_a_recoverable_original
    BEFORE INSERT ON settlement.file
    FOR EACH ROW
    EXECUTE FUNCTION settlement.file_readmits_a_recoverable_original();
