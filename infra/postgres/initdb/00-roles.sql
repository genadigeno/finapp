-- Cluster roles for local development (P0-TSK-022, DATA_MIGRATIONS.md §6).
--
-- WHY THIS IS NOT A FLYWAY MIGRATION
--   A PostgreSQL role is a CLUSTER-level object, shared by every database in the cluster. A
--   Flyway migration belongs to one schema in one database, and its history table records what
--   was applied to that database. Creating a role from a migration would mean:
--     * the platform schema's migration history claiming ownership of an object outside it;
--     * a second database in the same cluster failing or conflicting on the same CREATE ROLE
--       (CI creates scratch databases to verify migrations apply to an empty database);
--     * the migrator needing CREATEROLE - the ability to invent roles - which is precisely the
--       privilege a role confined to DDL on one schema should not hold.
--
--   So: role EXISTENCE is infrastructure, role PRIVILEGES on our tables are schema. Grants live
--   in the migration that creates the table, exactly as DATA_MIGRATIONS.md §6 requires.
--
--   In a deployed environment this file's job belongs to Terraform or a managed-database
--   operator. It is here because a laptop has no such thing, and because the roles must exist
--   before the first migration runs.
--
-- WHY TWO ROLES AND NOT ONE
--   Three invariants are enforced at the privilege level and nowhere else:
--     INV-LED-03   posted journal entries and lines are immutable
--     INV-HIST-01  financial history is never edited
--     INV-HIST-03  audit records are append-only
--   "Enforced at the privilege level" means the application role must LACK the privilege. A
--   guarantee held only in application code is not a guarantee: the next writer is a job, an
--   operator tool, or a psql session, and none of them read our Java.
--
-- SUPERUSER BYPASSES ALL OF IT
--   A superuser ignores every permission check. Running the application as the cluster
--   superuser - which this project did until now - does not merely weaken these invariants, it
--   makes them untestable: an immutability test that connects as a superuser passes whatever
--   the grants say. finapp_app is deliberately NOSUPERUSER, NOCREATEDB, NOCREATEROLE, and
--   AuditImmutabilityTest asserts that it is, so the test cannot become vacuous by someone
--   later granting it more.
--
-- SECURITY: these passwords are local-development-only throwaway values, named so they cannot
-- be mistaken for secrets. Real credential handling is P0-TSK-031.

-- Idempotent: this script runs on first container initialisation, and a developer with an
-- existing volume applies it by hand (see README). Neither must fail if the role is present.
DO $$
BEGIN
    -- Owns the platform schema and everything in it. DDL only; it is not what the application
    -- connects as. NOT a superuser: a migrator that could bypass grants could also drop the
    -- protection it is applying.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'finapp_migrator') THEN
        CREATE ROLE finapp_migrator
            LOGIN PASSWORD 'local-development-only-not-a-secret'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;

    -- What the application connects as. Holds no DDL and only the DML each table genuinely
    -- requires, granted per table by the migration that creates it.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'finapp_app') THEN
        CREATE ROLE finapp_app
            LOGIN PASSWORD 'local-development-only-not-a-secret'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

-- The migrator needs to create its schema on first run; nothing else does.
GRANT CREATE, CONNECT ON DATABASE finapp TO finapp_migrator;
GRANT CONNECT ON DATABASE finapp TO finapp_app;

-- Default-deny for everyone else, matching V001's stance on the schema. PostgreSQL grants
-- CREATE on the public schema and CONNECT on the database to PUBLIC by default, which would
-- let any role in the cluster create objects beside ours.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE finapp FROM PUBLIC;
