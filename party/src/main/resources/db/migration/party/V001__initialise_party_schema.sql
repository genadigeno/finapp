-- Initialise the `party` module's schema.
--
-- Schema-per-module (ADR-0006): the `party` schema is owned exclusively by the party
-- module. No other module reads or writes tables here; cross-module access goes through this
-- module's published API, and there are **no foreign keys across module schemas**.
--
-- That last rule is load-bearing for this pair specifically. `identity` references a Party by
-- identifier only, never by a database foreign key (ADR-0029): an FK across a module boundary is
-- coupling neither Gradle nor ArchUnit can see, and it would turn ADR-0001's stated escape —
-- extracting a module into its own service — into a data migration. Referential integrity across
-- that edge is a domain rule, enforced by the registration transaction.
--
-- This migration creates no tables. `party` and `customer` are P1-TSK-005.

COMMENT ON SCHEMA party IS
    'Owned by the party module. Party, Customer and profile data — who exists and what relationship they hold. RESTRICTED-PII. No cross-schema foreign keys. See docs/architecture/MODULE_ARCHITECTURE.md.';

-- Default-deny. PostgreSQL historically granted broad privileges on schemas to PUBLIC, and an
-- object created here must not become readable or writable by every role in the cluster by
-- default. Access is granted explicitly, per role, per object — and each table's grants arrive
-- with the migration that creates it, never by ALTER DEFAULT PRIVILEGES, so a table that must
-- NOT receive the usual set cannot be given it by a default nobody revisited.
REVOKE ALL ON SCHEMA party FROM PUBLIC;

-- USAGE only. Without it the application role cannot see the schema at all, whatever it later
-- holds on the tables in it; with it and nothing else, it can see the schema and touch nothing.
GRANT USAGE ON SCHEMA party TO finapp_app;
