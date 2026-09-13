-- Initialise the `ledger` module's schema (P3-TSK-001).
--
-- Schema-per-module (ADR-0006): the `ledger` schema is owned exclusively by the ledger module. No
-- other module reads or writes tables here; cross-module access goes through this module's
-- published API, and there are **no foreign keys across module schemas**.
--
-- This schema will hold the authoritative financial record, and three invariants depend on how it
-- is created rather than on anything written into it later:
--
--   INV-LED-03  posted entries and lines are immutable
--   INV-HIST-01 financial history is never edited
--   INV-LED-04  the ledger is the sole writer of postings
--
-- All three are enforced at DB-PRIVILEGE: the application role will hold INSERT and SELECT on the
-- journal tables and nothing else. That enforcement is only available because the objects are
-- owned by the migrator - a role that cannot bypass the grants it applies - and because each
-- table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The ledger account table is P3-TSK-002.

COMMENT ON SCHEMA ledger IS
    'Owned by the ledger module. The authoritative financial record: chart of accounts, journal entries and lines, balance projection, holds. Journal tables are INSERT/SELECT only for the application role (INV-LED-03, INV-HIST-01); no other module writes here (INV-LED-04). No cross-schema foreign keys. See docs/domain/LEDGER_MODEL.md.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by default.
REVOKE ALL ON SCHEMA ledger FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later holds
-- on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here the
-- same set, and the tables that matter most in this schema are exactly the ones that must NOT receive
-- UPDATE or DELETE. Each table's grants are written by the migration that creates it, where a
-- reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA ledger TO finapp_app;
