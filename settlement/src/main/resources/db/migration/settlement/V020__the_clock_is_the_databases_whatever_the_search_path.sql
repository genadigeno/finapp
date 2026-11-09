-- Settlement's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY; reason=each body is its latest definition byte for byte but for pg_catalog. before each clock call
--
-- WHY
--   A function of this schema is invoker's rights with no search_path of its own, and it called the clock functions
--   unqualified. PostgreSQL searches pg_catalog first only when the path does not name it: a session whose path puts
--   a schema of its own before pg_catalog resolves statement_timestamp() to that schema's function, so what a
--   judgement reads as now could be moved without touching a row or a rule.
--
-- WHAT CHANGES, AND ONLY THAT
--   1 function(s), 2 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V018, the last
--   definition of each. CURRENT_TIMESTAMP, CURRENT_DATE and LOCALTIMESTAMP are grammar, not functions, and are never
--   resolved through a path.
--
-- WHAT IT DOES NOT CHANGE
--   No table, column, row or grant, and no existing trigger. It moves no money. No judgement on any path a production
--   role has: finapp_app holds no TRUNCATE and no CREATE on any schema, and the pg_catalog clocks are the ones its
--   path already resolved to. CREATE OR REPLACE keeps each function's OID, so every trigger stays bound.
--
-- WHAT IT DOES NOT CLAIM
--   A trigger is an object its owner can drop or disable, and a body can be replaced: this closes the unreviewed
--   path, a TRUNCATE that happens to be run or a role's path, not a deliberate migration, which is reviewed and
--   recorded in the history.

-- settlement.source_moves_forward_only: V018's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION settlement.source_moves_forward_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.code IS DISTINCT FROM OLD.code
            OR NEW.kind IS DISTINCT FROM OLD.kind THEN
        RAISE EXCEPTION 'a settlement source''s identity is frozen (P8-TSK-002)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED') THEN
        RAISE EXCEPTION 'a settlement source retires once and never returns (P8-TSK-002): a re-opened source is a NEW source';
    END IF;
    IF NEW.next_sequence < OLD.next_sequence THEN
        RAISE EXCEPTION 'a settlement source''s sequence never walks backwards (P8-TSK-002)';
    END IF;
    IF NEW.admitted_on > OLD.admitted_on THEN
        RAISE EXCEPTION 'a settlement source''s admission never moves later (P14-TSK-025): a later day would excuse a month it served';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' AND NEW.retired_on IS NULL THEN
        IF has_column_privilege(current_user, 'settlement.source', 'retired_on', 'UPDATE') THEN
            RAISE EXCEPTION 'a retirement by a writer that can state its day must state it (P14-TSK-025): retired_on, the last day the source served, once that month''s run counts';
        END IF;
        NEW.retired_on := (pg_catalog.now() AT TIME ZONE 'UTC')::date;
    END IF;
    IF NEW.retired_on IS DISTINCT FROM OLD.retired_on THEN
        IF NEW.retired_on IS NULL OR NEW.retired_on < OLD.retired_on THEN
            RAISE EXCEPTION 'a settlement source''s retirement is never cleared or moved earlier (P14-TSK-025): an earlier day would excuse a month it served';
        END IF;
        IF NEW.retired_on > (pg_catalog.now() AT TIME ZONE 'UTC')::date THEN
            RAISE EXCEPTION 'a settlement source is retired on a day that has come (P14-TSK-025): its door shuts now';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
