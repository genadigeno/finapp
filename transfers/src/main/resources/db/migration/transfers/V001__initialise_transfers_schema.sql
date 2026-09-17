-- Initialise the `transfers` module's schema (P4-TSK-001).
--
-- Schema-per-module (ADR-0006): the `transfers` schema is owned exclusively by the transfers
-- module. No other module reads or writes tables here; cross-module access goes through this
-- module's published API, and there are **no foreign keys across module schemas** - a transfer
-- references its source and destination ledger accounts, its owning customer and its posted
-- journal entry by value, never by REFERENCES (the ADR-0029 rule, applied to the ADR-0043
-- boundary).
--
-- This schema will hold the MOVEMENT - the Transfer, its append-only lifecycle history, and the
-- Beneficiary - and never a posting. The ledger is the sole writer of journal entries
-- (INV-LED-04); a transfer commands its posting through the ledger API in the one local
-- transaction ADR-0043 decides, and what is recorded here is the domain outcome that transaction
-- committed.
--
-- The privilege floor matters before the first table does: PHASE_4_PLAN.md §8 commits
-- `transfers.transfer` to `SELECT, INSERT` with UPDATE column-narrowed to the reversal columns,
-- `transfers.transfer_event` to `SELECT, INSERT` alone (append-only history), and the lifecycle
-- transition trigger to binding every writer. Those grant shapes are only available because the
-- objects are owned by the migrator - a role that cannot bypass the grants it applies - and
-- because each table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The transfer tables are P4-TSK-004; the beneficiary is
-- P4-TSK-006.

COMMENT ON SCHEMA transfers IS
    'Owned by the transfers module. Movement of funds between two internal accounts: Transfer, lifecycle history, Beneficiary. Commands postings through the ledger and writes none (INV-LED-04, ADR-0043); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md §4.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by default.
REVOKE ALL ON SCHEMA transfers FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose UPDATE must be
-- narrowed to enumerated columns or refused entirely. Each table's grants are written by the
-- migration that creates it, where a reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA transfers TO finapp_app;
