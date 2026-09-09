-- Initialise the `consent` module's schema.
--
-- Schema-per-module (ADR-0006): the `consent` schema is owned exclusively by the consent
-- module. No other module reads or writes tables here; cross-module access goes through this
-- module's published API, and there are **no foreign keys across module schemas**.
--
-- The tables that will live here are an append-only history (INV-CNS-02): grants and
-- withdrawals as immutable facts, INSERT/SELECT only for the application role - the audit-table
-- mechanism, available at DB-PRIVILEGE rank only because the migrator owns the objects and the
-- grants are explicit per table.
--
-- This migration creates no tables. The consent text and record tables are P2-TSK-017.

COMMENT ON SCHEMA consent IS
    'Owned by the consent module. Versioned consent texts and the append-only grant/withdraw history - the lawful basis for processing, distinct from authentication and authorization (CLAUDE.md §Domain Distinctions). No cross-schema foreign keys. See docs/architecture/MODULE_ARCHITECTURE.md.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default. Access is granted explicitly, per role, per object — and each table's grants arrive
-- with the migration that creates it, never by ALTER DEFAULT PRIVILEGES, so a table that must
-- NOT receive the usual set cannot be given it by a default nobody revisited.
REVOKE ALL ON SCHEMA consent FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
GRANT USAGE ON SCHEMA consent TO finapp_app;
