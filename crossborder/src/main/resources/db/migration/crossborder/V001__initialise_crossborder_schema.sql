-- Initialise the `crossborder` module's schema (P9-TSK-001).
--
-- Schema-per-module (ADR-0006): the `crossborder` schema is owned exclusively by the crossborder module. No other
-- module reads or writes tables here; cross-module access goes through this module's published
-- API, and there are **no foreign keys across module schemas** (the ADR-0029 rule).
--
-- This schema will hold the instruction to pay abroad and the corridor rules governing it: the
-- versioned, four-eyes Corridor Policy with its availability, the Cross-Border Beneficiary -
-- known by an opaque provider reference, its country, currency and entity type, never a name
-- (INV-RAIL-03; names are kyc's, encrypted) - the Corridor Selection, the Cross-Border Payment
-- with its history (never reversed - INV-REV-03; what the customer was shown is exactly what
-- is held, posted and instructed - INV-XB-03) and the Cancellation Request, born once and never
-- updated - and **never the price or the execution**: quotes and trades are `fx`'s, outbound
-- credits `payments`' (ADR-0079). Every posting is COMMANDED through the ledger and never
-- written here (INV-LED-04).
--
-- The privilege floor matters before the first table does: PHASE_9_PLAN.md commits the payment
-- machine to a generated CHECK and an every-writer edge trigger, the cancellation request to
-- insert-only, the beneficiary's screening mirror to the kyc decision's own transaction. Those
-- shapes are only available because the objects are owned by the migrator - a role that cannot
-- bypass the grants it applies - and because each table's grants arrive with the migration
-- that creates it.
--
-- This migration creates no tables. Corridors are P9-TSK-015, beneficiaries P9-TSK-017, the
-- payment P9-TSK-019.

COMMENT ON SCHEMA crossborder IS
    'Owned by the crossborder module. The instruction to pay abroad and the corridor rules: Corridor Policy (versioned, four-eyes), Cross-Border Beneficiary (an opaque provider reference, never a name - INV-RAIL-03), Corridor Selection, Cross-Border Payment (never reversed - INV-REV-03; what was shown is what is held, posted and instructed - INV-XB-03), Cancellation Request (born once). Pricing is fx''s and execution payments'' (ADR-0079); postings are commanded through the ledger and never written (INV-LED-04); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md crossborder.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA crossborder FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table. Each table's grants are written by the migration that creates it, where a
-- reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA crossborder TO finapp_app;
