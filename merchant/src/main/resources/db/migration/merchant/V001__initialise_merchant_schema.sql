-- Initialise the `merchant` module's schema (P6-TSK-001).
--
-- Schema-per-module (ADR-0006): the `merchant` schema is owned exclusively by the merchant
-- module. No other module reads or writes tables here; cross-module access goes through this
-- module's published API, and there are **no foreign keys across module schemas** - a merchant
-- references its legal party, its KYB decision and its payable ledger account by value, never
-- by REFERENCES (the ADR-0029 rule).
--
-- This schema will hold the commercial counterparty - the Merchant and its machine, the
-- MerchantApiKey (hashed, never recoverable - INV-IDN-01 applied to the platform's fourth
-- authentication vocabulary, ADR-0052), the versioned FeeSchedule (immutable once effective -
-- INV-MER-03), the PayoutDestination with its proposer-is-not-approver flow (INV-AUD-04's first
-- implemented subject) and the MerchantPayout with its dispatch discipline (ADR-0051) - and
-- **never a balance**. What the platform owes a merchant is the merchant's payable LEDGER
-- POSITION, derived from postings and stored nowhere (INV-MER-02): no table in this schema will
-- ever hold a payable, balance or amount-owed column, and the schema sweep that proves it needs
-- this schema to exist to sweep.
--
-- The privilege floor matters before the first table does: PHASE_6_PLAN.md §8 commits the fee
-- schedule's versions to freeze-by-trigger, the key to hashed-at-rest storage, the destination
-- to a proposer <> approver CHECK, and the payout to insert-carries-outcome with our minted
-- idempotency reference. Those grant and constraint shapes are only available because the
-- objects are owned by the migrator - a role that cannot bypass the grants it applies - and
-- because each table's grants arrive with the migration that creates it.
--
-- This migration creates no tables. The merchant and key tables are P6-TSK-002/-003, the fee
-- tables P6-TSK-004, the destination P6-TSK-011, the payout P6-TSK-012.

COMMENT ON SCHEMA merchant IS
    'Owned by the merchant module. The commercial counterparty: Merchant, MerchantApiKey, FeeSchedule (versioned, immutable), PayoutDestination (four-eyes), MerchantPayout. The payable is a ledger position and is stored nowhere here (INV-MER-02); postings are commanded through the ledger and never written (INV-LED-04); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md §4.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA merchant FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table - the schedule versions frozen, the key's hash unreadable beyond lookup
-- needs, the payout insert-carries-outcome. Each table's grants are written by the migration
-- that creates it, where a reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA merchant TO finapp_app;
