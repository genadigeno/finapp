-- Initialise the `settlement` module's schema (P8-TSK-001).
--
-- Schema-per-module (ADR-0006): the `settlement` schema is owned exclusively by the settlement
-- module. No other module reads or writes tables here; cross-module access goes through this
-- module's published API, and there are **no foreign keys across module schemas** - a settlement
-- line names the payment, withdrawal, payout or dispute it settles by identifier, never by
-- REFERENCES (the ADR-0029 rule).
--
-- This schema will hold what external institutions SAY happened - the SettlementFile (bytes
-- verbatim, screened at the door, encrypted under its own key with the AAD bound, its stored
-- checksum equal to the received bytes' - INV-HIST-02, ADR-0066), the SettlementBatch with its
-- control totals (accepted whole or rejected whole, recognised exactly once from its own stored
-- evidence - INV-SET-04), the canonical settlement lines and their references, the refused
-- deliveries (metadata only - a PAN-bearing file stores nothing else, INV-PAY-02 taking
-- precedence), and the pull permits (ADR-0066 §7, strictly advancing on every renewal) - and
-- **never an expectation, a break or a suspense item**: what the platform EXPECTED belongs to
-- `reconciliation` (ADR-0064), and no table here will ever hold a disposition. Recognition
-- postings are COMMANDED through the ledger and never written here (INV-LED-04).
--
-- The privilege floor matters before the first table does: PHASE_8_PLAN.md §8 commits every
-- evidence table to append-only grants - `finapp_app` receives no DELETE anywhere in this
-- schema, ever (the break-immutability exit criterion's settlement half), file content to
-- SELECT/INSERT with its columns narrowed, and the acceptance columns to once-only by trigger.
-- Those grant shapes are only available because the objects are owned by the migrator - a role
-- that cannot bypass the grants it applies - and because each table's grants arrive with the
-- migration that creates it.
--
-- This migration creates no tables. The source register and file store are P8-TSK-002, the
-- door P8-TSK-003, the PSP format P8-TSK-008, acceptance P8-TSK-009.

COMMENT ON SCHEMA settlement IS
    'Owned by the settlement module. External settlement evidence and its recognition: SettlementFile (bytes verbatim, encrypted, checksummed), SettlementBatch (accepted whole or rejected whole, recognised once - INV-SET-04), canonical lines, refused deliveries (metadata only), pull permits. Expectations, breaks and suspense are reconciliation''s (ADR-0064); postings are commanded through the ledger and never written (INV-LED-04); no cross-schema foreign keys. See MODULE_ARCHITECTURE.md M8.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default.
REVOKE ALL ON SCHEMA settlement FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
--
-- Deliberately NO `ALTER DEFAULT PRIVILEGES`. A default grant would hand every future table here
-- the same set, and the tables this schema will hold are exactly ones whose grants must be
-- narrowed per table - evidence append-only with no DELETE for the application role anywhere,
-- the acceptance columns once-only, the file content unreadable beyond what serving it needs.
-- Each table's grants are written by the migration that creates it, where a reviewer reads them
-- beside the table they apply to.
GRANT USAGE ON SCHEMA settlement TO finapp_app;
