-- Initialise the `accounting` module's schema (P14-TSK-001).
--
-- Schema-per-module (ADR-0006): the `accounting` schema is owned exclusively by the accounting module. No other module
-- reads or writes tables here; cross-module access goes through this module's published API, and there are **no
-- foreign keys across module schemas** (the ADR-0029 rule) - a GL mapping rule names its ledger purpose as a string, a
-- break names its journal entry by identifier, a prior-period adjustment names the ledger's adjustment proposal by
-- identifier, never by constraint. The schema itself is created by Flyway's `schemas` setting, as every module's is, so
-- the migrator owns it from the first object.
--
-- This schema will hold the accounting representation and the reporting record, and none of the money (ADR-0113,
-- INV-ACC-06): the GL mapping versions with their GL accounts and rules (V002, P14-TSK-003), the accounting periods and
-- their events (P14-TSK-005), report runs with their frozen content and hash (P14-TSK-008), the breaks (P14-TSK-010),
-- the close runs and checks (P14-TSK-011), the prior-period adjustment records (P14-TSK-013), the valuation rate sets
-- (P14-TSK-014) and the regulatory obligations and submissions (P14-TSK-018) - numbered in landing order. **Never a
-- balance and never a line amount**: every GL figure is a function of the ledger's journal lines and the mapping
-- version the period pins; the only stored figures are a report run's frozen output, which is output, not truth.
--
-- The privilege floor matters before the first table does: PHASE_14_PLAN.md section 9 commits every table to SELECT,
-- INSERT for the application role, an UPDATE only of the status columns of the machine rows through conditional edges,
-- report content frozen by trigger, and never DELETE for any role. Those shapes are only available because the objects
-- are owned by the migrator - a role that cannot bypass the grants it applies - and because each table's grants arrive
-- with the migration that creates it.
--
-- This migration creates no tables. The GL mapping version is P14-TSK-003's.

COMMENT ON SCHEMA accounting IS
    'Owned by the accounting module. The accounting representation of the one ledger and its reporting record: GL mapping versions (four-eyes) with their GL accounts and rules, accounting periods and their close runs and checks, valuation rate sets, accounting breaks, prior-period adjustment records, report runs (frozen output) and regulatory submissions. Never the money: no table holds a balance or a line amount (INV-ACC-06), the journal is written only through the ledger''s own ACCOUNTING-origin adjustment door, reporting never writes (INV-ACC-14); no role deletes; no cross-schema foreign keys. See MODULE_ARCHITECTURE.md accounting.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an object created here must
-- not become readable or writable by every role in the cluster by default.
REVOKE ALL ON SCHEMA accounting FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later holds on the tables in
-- it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here the same set - and a
-- default UPDATE or DELETE would make every activated mapping version, closed period and FINAL report run editable the
-- moment its table was created, which is exactly what INV-ACC-04, INV-ACC-07 and INV-ACC-10 forbid. Each table's grants
-- are written by the migration that creates it, where a reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA accounting TO finapp_app;
