-- Initialise the `paymentmethods` module's schema (P5-TSK-001).
--
-- Schema-per-module (ADR-0006): the `paymentmethods` schema is owned exclusively by the
-- paymentmethods module - the platform's PCI boundary (MODULE_ARCHITECTURE.md M7). No other
-- module reads or writes tables here; cross-module access goes through this module's published
-- API, and there are **no foreign keys across module schemas** - a payment method references its
-- owning Party by value, never by REFERENCES (the ADR-0029 rule).
--
-- This schema will hold the tokenised instrument (P5-TSK-004): a token reference and display
-- metadata, and STRUCTURALLY NOTHING ELSE. The ceiling is stated before the first column exists
-- (the ADR-0022 discipline): no column in this schema may ever be classified to admit a PAN, a
-- CVV, track data or any value from which an instrument could be reconstructed (INV-PAY-02) -
-- a tokenisation provider being unavailable fails the operation and never falls back to storing
-- raw detail. The information_schema-derived sweeps that prove the absence arrive with the table
-- they sweep.
--
-- This migration creates no tables. The payment_method table is P5-TSK-004.

COMMENT ON SCHEMA paymentmethods IS
    'Owned by the paymentmethods module - the PCI boundary. Tokenised instrument references and display metadata only; no column may ever admit reconstructable instrument data (INV-PAY-02); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md §4 and M7.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA paymentmethods FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`, for the standing reason - and here the table that
-- is coming is exactly one whose grants a reviewer must read beside its columns: the instrument
-- table's UPDATE will be narrowed to the detach columns (P5-TSK-004), the beneficiary precedent.
GRANT USAGE ON SCHEMA paymentmethods TO finapp_app;
