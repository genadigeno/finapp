-- Initialise the `fx` module's schema (P9-TSK-001).
--
-- Schema-per-module (ADR-0006): the `fx` schema is owned exclusively by the fx module. No other
-- module reads or writes tables here; cross-module access goes through this module's published
-- API, and there are **no foreign keys across module schemas** (the ADR-0029 rule).
--
-- This schema will hold currency conversion and the platform's FX exposure: the versioned,
-- four-eyes Pricing Policy with pair and provider availability, the Exchange Rate snapshot
-- (the independent reference, plausibility and disclosure only), the FX Quote - a frozen
-- posting plan with its rate provenance, reference -> provider -> internal -> customer ->
-- executed -> cover-executed, every link stored (INV-FX-01, INV-FX-02, ADR-0075) - the FX
-- Trade that posts exactly the plan in the acceptance's own transaction (ADR-0076), and the
-- FX Cover, sent back-to-back to the provider exactly once whatever it answers (ADR-0077) -
-- and **never the instruction to pay abroad**: corridors, beneficiaries and cross-border
-- payments are `crossborder`'s (ADR-0079). `FX_POSITION` is the ledger's account per currency,
-- explained by this module's proof, never a table here; every posting is COMMANDED through
-- the ledger and never written here (INV-LED-04).
--
-- The privilege floor matters before the first table does: PHASE_9_PLAN.md commits the quote's
-- provenance columns to NOT NULL and frozen by trigger, the trade to UNIQUE (quote_id) and
-- immutable, the cover's execution fact to append-only. Those grant and constraint shapes are
-- only available because the objects are owned by the migrator - a role that cannot bypass
-- the grants it applies - and because each table's grants arrive with the migration that
-- creates it.
--
-- This migration creates no tables. The quote and rate chain are P9-TSK-005...-008, the trade
-- P9-TSK-009, the cover P9-TSK-012.

COMMENT ON SCHEMA fx IS
    'Owned by the fx module. Currency conversion and the platform''s FX exposure: Pricing Policy (versioned, four-eyes), Exchange Rate snapshot (the independent reference), FX Quote (a frozen posting plan with stored rate provenance - INV-FX-01, INV-FX-02), FX Trade (posts exactly the plan), FX Cover (back-to-back with the provider, exactly once), Trade Reversal. FX_POSITION is the ledger''s account per currency, never a table here; postings are commanded through the ledger and never written (INV-LED-04); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md fx.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA fx FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table. Each table's grants are written by the migration that creates it, where a
-- reviewer reads them beside the table they apply to.
GRANT USAGE ON SCHEMA fx TO finapp_app;
