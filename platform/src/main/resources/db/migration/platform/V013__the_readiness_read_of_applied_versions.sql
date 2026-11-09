-- The readiness guard's one read of what has been applied (P15-TSK-005; DATA_MIGRATIONS.md sections 4.1 and 6).
--
-- WHY A FUNCTION
--   A replica built for a schema it does not find must not take traffic, so it has to read each schema's applied version. The
--   application role holds no access to any flyway_schema_history: that table is the record of what was applied, and
--   ApplicationRoleGrantsTest pins that the role cannot read it. Granting it twenty-one tables would widen what the role can
--   see (scripts, checksums, who ran what) to answer one question. This function answers exactly that question and nothing else.
--
-- ONE MIGRATION, ALL TWENTY-ONE SCHEMAS
--   SECURITY DEFINER runs as this schema's owner, the migrator, and the migrator owns every schema's history table: Flyway
--   connects as it in every module's build and in the migrator image. So the one function reads them all, and no module needs a
--   migration of its own (credit.consume_decision's shape, ADR-0091; platform V012's).
--
-- WHAT IT RETURNS
--   One row per applied migration of every schema it can read: the schema's name and the version as Flyway wrote it. Only
--   rows with success = true: on PostgreSQL a failed transactional migration is rolled back and leaves no row, and a failed
--   non-transactional one leaves success = false with an effect that may be partial, so it is not an applied version. A
--   repeatable migration has no version and is left out. All the rows are returned rather than a maximum, because the maximum
--   of the text is wrong ('9' sorts after '10'): the application compares them as versions (SchemaVersion).
--
-- THE TABLES ARE FOUND IN THE CATALOGUE
--   A schema added by a later module is covered with no edit here, and a schema whose history does not exist yet returns no
--   row, which the guard reads as older. So does a history table this role cannot read: it is skipped rather than failing every
--   other schema's answer, and the absence fails closed. The name comes from pg_namespace and goes through format's %I and %L,
--   and the search_path is pinned, so neither a schema's name nor a caller's path can change what runs.
--
-- THE GRANTS
--   EXECUTE to the application role alone. finapp_reporting has no use for it and does not get it.
--
-- COST
--   Readiness never waits on this: a daemon thread reads it, at most once a minute on a healthy replica (SchemaVersionGuard). One
--   statement on one pooled connection for every schema together; a connection per schema per probe would spend the budget.

CREATE FUNCTION platform.applied_migration_versions()
    RETURNS TABLE (schema_name text, version text)
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_schema name;
BEGIN
    FOR v_schema IN
        SELECT n.nspname
          FROM pg_catalog.pg_class c
          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
         WHERE c.relname = 'flyway_schema_history'
           AND c.relkind IN ('r', 'p')
           AND pg_catalog.has_table_privilege(c.oid, 'SELECT')
    LOOP
        RETURN QUERY EXECUTE pg_catalog.format(
            'SELECT %L::text, h.version::text FROM %I.flyway_schema_history h WHERE h.success AND h.version IS NOT NULL',
            v_schema, v_schema);
    END LOOP;
END;
$$;

COMMENT ON FUNCTION platform.applied_migration_versions() IS
    'The readiness guard''s one read of what has been applied (P15-TSK-005): every successful versioned migration of every '
    'schema''s flyway_schema_history, as (schema, version). SECURITY DEFINER as the migrator, search_path pinned; EXECUTE to the '
    'application role only, which still cannot read the history tables.';

REVOKE ALL ON FUNCTION platform.applied_migration_versions() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.applied_migration_versions() TO finapp_app;
