-- Initialise the `accounts` module's schema (P3-TSK-011).
--
-- Schema-per-module (ADR-0006): the `accounts` schema is owned exclusively by the accounts module.
-- No other module reads or writes tables here; cross-module access goes through this module's
-- published API, and there are **no foreign keys across module schemas** - a Customer Account
-- references its ledger account(s) by value, never by REFERENCES (the ADR-0029 rule, applied to
-- the ADR-0042 boundary).
--
-- This schema will hold the customer account PRODUCT - the agreement, its status, its lifecycle -
-- and never a balance. A balance column on a product row is `balance = balance + amount` waiting
-- to happen (INV-BAL-01, ADR-0042); the money lives in the `ledger` schema and nowhere else.
--
-- The privilege floor matters before the first table does: PHASE_3_PLAN.md §8 commits
-- `accounts.customer_account` to `SELECT, INSERT` with UPDATE column-narrowed to
-- (status, status_changed_at), and that grant shape is only available because the objects are
-- owned by the migrator - a role that cannot bypass the grants it applies - and because each
-- table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The customer account table is P3-TSK-012.

COMMENT ON SCHEMA accounts IS
    'Owned by the accounts module. The customer account and wallet product: agreement, status, lifecycle, ownership. Carries no balance - balances are the ledger''s (ADR-0042); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md §4 and ADR-0042.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by default.
REVOKE ALL ON SCHEMA accounts FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the first table this schema will hold is exactly one whose UPDATE must be
-- narrowed to two columns. Each table's grants are written by the migration that creates it,
-- where a reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA accounts TO finapp_app;
